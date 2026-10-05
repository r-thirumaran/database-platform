package org.dbplatform.proxy.net;

import io.micrometer.core.instrument.Counter;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.identity.IdentityResolver;
import org.dbplatform.proxy.registry.LiveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Per-connection state and helpers shared by the protocol handlers: client/backend sockets and streams,
 * the {@link LiveConnection} record, quota admission, backend connect with latency metrics, event
 * emission and cleanup. Closing the context closes both sockets and releases the registry slot.
 *
 * <p><b>Handshake deadline.</b> The whole connect handshake (client packets, backend connect, backend
 * replies, redirects) must finish within {@code DBP_PROXY_HANDSHAKE_TIMEOUT_MS} of accept. The deadline
 * is enforced as a socket read timeout that is re-armed with the <em>remaining</em> time before every
 * read ({@link #armHandshakeTimeout(Socket)}): a client that sends one byte and stalls, or drips one
 * byte per read, is dropped when the deadline passes, not after an unbounded series of per-read timeouts.
 * Once the data path is transparent ({@link #pump()}) the deadline no longer applies.
 */
public final class ConnectionContext implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionContext.class);

    private final ProxyRuntime rt;
    private final ListenerRuntime listener;
    private final Socket client;
    private final LiveConnection live;
    private final InputStream clientIn;
    private final OutputStream clientOut;
    private final Counter bytesInCounter;
    private final Counter bytesOutCounter;
    private final String capReason;
    private final long handshakeDeadlineNanos;

    private Socket backend;
    private InputStream backendIn;
    private OutputStream backendOut;
    private boolean admitted;
    private boolean openEmitted;
    private volatile boolean handshake = true;

    ConnectionContext(ProxyRuntime rt, ListenerRuntime listener, Socket client, LiveConnection live, String capReason) throws IOException {
        this.rt = rt;
        this.listener = listener;
        this.client = client;
        this.live = live;
        this.capReason = capReason;
        this.handshakeDeadlineNanos = System.nanoTime() + rt.settings().handshakeTimeoutMs() * 1_000_000L;
        this.clientIn = new BufferedInputStream(new HandshakeGuardedInput(client.getInputStream(), client), rt.settings().bufferBytes());
        this.clientOut = client.getOutputStream();
        this.bytesInCounter = rt.metrics().bytesInCounter(live.listener());
        this.bytesOutCounter = rt.metrics().bytesOutCounter(live.listener());
    }

    public ProxyRuntime runtime() {
        return rt;
    }

    public ProxySettings settings() {
        return rt.settings();
    }

    public ListenerConfig config() {
        return listener.config();
    }

    public IdentityResolver identity() {
        return rt.identity();
    }

    public LiveConnection live() {
        return live;
    }

    public Socket client() {
        return client;
    }

    public InputStream clientIn() {
        return clientIn;
    }

    public OutputStream clientOut() {
        return clientOut;
    }

    public Socket backend() {
        return backend;
    }

    public InputStream backendIn() {
        return backendIn;
    }

    public OutputStream backendOut() {
        return backendOut;
    }

    Counter bytesInCounter() {
        return bytesInCounter;
    }

    Counter bytesOutCounter() {
        return bytesOutCounter;
    }

    /** Non-null when the listener's {@code maxConnections} cap is exceeded: handlers must refuse. */
    public String capReason() {
        return capReason;
    }

    /** Milliseconds left until the handshake deadline (negative when it has passed). */
    public long handshakeRemainingMillis() {
        return (handshakeDeadlineNanos - System.nanoTime()) / 1_000_000L;
    }

    /**
     * Sets the socket's read timeout to the time remaining until the handshake deadline, or throws
     * {@link SocketTimeoutException} when the deadline has already passed. Call before every read that
     * is part of the handshake; the guarded client/backend streams do this for every physical read too.
     */
    public void armHandshakeTimeout(Socket socket) throws IOException {
        long remaining = handshakeRemainingMillis();
        if (remaining <= 0) {
            throw new SocketTimeoutException("handshake deadline of " + settings().handshakeTimeoutMs() + " ms exceeded");
        }
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, remaining)));
    }

    public void armClientHandshakeTimeout() throws IOException {
        armHandshakeTimeout(client);
    }

    public void armBackendHandshakeTimeout() throws IOException {
        if (backend != null) {
            armHandshakeTimeout(backend);
        }
    }

    /** Quota check + registry registration. Returns null when admitted, else the refusal reason. */
    public String admit() {
        String reason = rt.quotas().admit(live);
        if (reason == null) {
            admitted = true;
        }
        return reason;
    }

    /**
     * Open (or re-open, for Oracle redirects) the backend socket; records latency and the correlation key.
     * The connect itself is bounded by the smaller of the connect timeout and the remaining handshake time.
     */
    public Socket connectBackend(String host, int port) throws IOException {
        if (backend != null) {
            closeQuietly(backend);
            backend = null;
        }
        long remaining = handshakeRemainingMillis();
        if (remaining <= 0) {
            throw new SocketTimeoutException("handshake deadline of " + settings().handshakeTimeoutMs() + " ms exceeded before connecting to " + host + ":" + port);
        }
        live.setState(LiveConnection.State.CONNECTING);
        live.setBackend(host, port);
        long t0 = System.nanoTime();
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(host, port), (int) Math.min(settings().connectTimeoutMs(), remaining));
            armHandshakeTimeout(s);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
        rt.metrics().recordConnect(live.listener(), host + ":" + port, System.nanoTime() - t0);
        backend = s;
        backendIn = new BufferedInputStream(new HandshakeGuardedInput(s.getInputStream(), s), settings().bufferBytes());
        backendOut = s.getOutputStream();
        live.setProxyLocal(s.getLocalAddress().getHostAddress(), s.getLocalPort());
        LOG.debug("{} backend {}:{} connected from {}:{}", live.id(), host, port, live.proxyLocalAddr(), live.proxyLocalPort());
        return s;
    }

    public void writeClient(byte[] bytes) throws IOException {
        clientOut.write(bytes);
        clientOut.flush();
        live.addBytesOut(bytes.length);
        bytesOutCounter.increment(bytes.length);
    }

    public void writeBackend(byte[] bytes) throws IOException {
        backendOut.write(bytes);
        backendOut.flush();
        live.addBytesIn(bytes.length);
        bytesInCounter.increment(bytes.length);
    }

    /** The proxy refused the connection (quota, unknown service, listener cap). */
    public void refused(String reason, String category) {
        live.setState(LiveConnection.State.REFUSED);
        live.setReason(reason);
        rt.metrics().refused(live.listener(), category);
        if (live.markTerminalEmitted()) {
            rt.events().refused(live, reason);
        }
        LOG.info("{} refused app={} ds={} service={}: {}", live.id(), live.application(), live.datasource(), live.requestedService(), reason);
    }

    /** The backend could not be reached or rejected the handshake. */
    public void backendFailed(String reason) {
        live.setReason(reason);
        rt.metrics().failed(live.listener());
        if (live.markTerminalEmitted()) {
            rt.events().backendFailed(live, reason);
        }
        LOG.warn("{} backend failure app={} ds={} backend={}: {}", live.id(), live.application(), live.datasource(), live.backend(), reason);
    }

    /** Handshake done, data path is transparent from now on. */
    public void opened() {
        live.setState(LiveConnection.State.ESTABLISHED);
        live.touch();
        if (!openEmitted) {
            openEmitted = true;
            rt.events().opened(live);
        }
        LOG.info("{} open app={} ({}) ds={} service={}->{} backend={} proxyPort={} program={} client={}:{}",
                live.id(), live.application(), live.identitySource(), live.datasource(), live.requestedService(),
                live.resolvedService(), live.backend(), live.proxyLocalPort(), live.program(), live.clientAddr(), live.clientPort());
    }

    /** Transparent copy in both directions until either side closes; emits CLOSE. */
    public void pump() throws IOException {
        if (backend == null) {
            throw new IllegalStateException("no backend");
        }
        handshake = false;
        client.setSoTimeout(0);
        String reason = Pump.run(this);
        closed(reason);
    }

    /** The connection is over: the registry slot is released first, then CLOSE is emitted (if OPEN was). */
    public void closed(String reason) {
        live.markClosed(reason);
        releaseSlot();
        if (openEmitted && live.markTerminalEmitted()) {
            rt.events().closed(live, reason);
            LOG.info("{} closed after {} ms in={} out={} ({})", live.id(), live.durationMillis(), live.bytesIn(), live.bytesOut(), reason);
        }
    }

    private void releaseSlot() {
        if (admitted) {
            admitted = false;
            rt.quotas().release(live);
        }
    }

    @Override
    public void close() {
        closeQuietly(client);
        if (backend != null) {
            closeQuietly(backend);
        }
        releaseSlot();
        if (live.state() != LiveConnection.State.CLOSED && live.state() != LiveConnection.State.REFUSED) {
            live.markClosed(live.reason() == null ? "closed" : live.reason());
        }
    }

    static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    /**
     * Re-arms the handshake deadline before every physical read while the handshake is in progress, so
     * the deadline holds inside {@code readFully} loops too; a plain pass-through once the pump runs.
     */
    private final class HandshakeGuardedInput extends FilterInputStream {
        private final Socket socket;

        HandshakeGuardedInput(InputStream in, Socket socket) {
            super(in);
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            guard();
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            guard();
            return in.read(b, off, len);
        }

        private void guard() throws IOException {
            if (handshake) {
                armHandshakeTimeout(socket);
            }
        }
    }
}
