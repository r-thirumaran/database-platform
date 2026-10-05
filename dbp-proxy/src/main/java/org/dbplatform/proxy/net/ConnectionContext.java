package org.dbplatform.proxy.net;

import io.micrometer.core.instrument.Counter;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.identity.IdentityResolver;
import org.dbplatform.proxy.registry.LiveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Per-connection state and helpers shared by the protocol handlers: client/backend sockets and streams,
 * the {@link LiveConnection} record, quota admission, backend connect with latency metrics, event
 * emission and cleanup. Closing the context closes both sockets and releases the registry slot.
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

    private Socket backend;
    private InputStream backendIn;
    private OutputStream backendOut;
    private boolean admitted;
    private boolean openEmitted;
    private boolean terminalEmitted;

    ConnectionContext(ProxyRuntime rt, ListenerRuntime listener, Socket client, LiveConnection live, String capReason) throws IOException {
        this.rt = rt;
        this.listener = listener;
        this.client = client;
        this.live = live;
        this.capReason = capReason;
        this.clientIn = new BufferedInputStream(client.getInputStream(), rt.settings().bufferBytes());
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

    public void setHandshakeTimeout() throws IOException {
        client.setSoTimeout(settings().handshakeTimeoutMs());
    }

    /** Quota check + registry registration. Returns null when admitted, else the refusal reason. */
    public String admit() {
        String reason = rt.quotas().admit(live);
        if (reason == null) {
            admitted = true;
        }
        return reason;
    }

    /** Open (or re-open, for Oracle redirects) the backend socket; records latency and the correlation key. */
    public Socket connectBackend(String host, int port) throws IOException {
        if (backend != null) {
            closeQuietly(backend);
            backend = null;
        }
        live.setState(LiveConnection.State.CONNECTING);
        live.setBackend(host, port);
        long t0 = System.nanoTime();
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(host, port), settings().connectTimeoutMs());
            s.setSoTimeout(settings().handshakeTimeoutMs());
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
        rt.metrics().recordConnect(live.listener(), host + ":" + port, System.nanoTime() - t0);
        backend = s;
        backendIn = new BufferedInputStream(s.getInputStream(), settings().bufferBytes());
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
        if (!terminalEmitted) {
            terminalEmitted = true;
            rt.events().refused(live, reason);
        }
        LOG.info("{} refused app={} ds={} service={}: {}", live.id(), live.application(), live.datasource(), live.requestedService(), reason);
    }

    /** The backend could not be reached or rejected the handshake. */
    public void backendFailed(String reason) {
        live.setReason(reason);
        rt.metrics().failed(live.listener());
        if (!terminalEmitted) {
            terminalEmitted = true;
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
        client.setSoTimeout(0);
        String reason = Pump.run(this);
        closed(reason);
    }

    /** The connection is over: the registry slot is released first, then CLOSE is emitted (if OPEN was). */
    public void closed(String reason) {
        live.markClosed(reason);
        releaseSlot();
        if (openEmitted && !terminalEmitted) {
            terminalEmitted = true;
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
}
