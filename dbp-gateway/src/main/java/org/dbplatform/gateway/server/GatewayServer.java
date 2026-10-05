package org.dbplatform.gateway.server;

import org.dbplatform.gateway.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Wire-protocol listener: a {@link ServerSocket} (optionally TLS) accepting on a platform thread and handing every
 * connection to a virtual thread running the handler produced by the factory.
 */
public final class GatewayServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayServer.class);

    private final GatewayConfig config;
    private final Function<Socket, Runnable> handlerFactory;
    private final IntSupplier inFlight;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("dbp-session-", 0).factory());
    private ServerSocket serverSocket;
    private Thread acceptor;

    /**
     * @param handlerFactory builds the per-connection handler
     * @param inFlight       number of statements currently executing (for graceful shutdown)
     */
    public GatewayServer(GatewayConfig config, Function<Socket, Runnable> handlerFactory, IntSupplier inFlight) {
        this.config = config;
        this.handlerFactory = handlerFactory;
        this.inFlight = inFlight;
    }

    public boolean isStopping() {
        return stopping.get();
    }

    /** Binds the listener and starts accepting. */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            return;
        }
        serverSocket = config.tlsKeystore().isPresent() ? tlsServerSocket() : new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(config.bindAddress()), config.port()), 512);
        acceptor = new Thread(this::acceptLoop, "dbp-acceptor");
        acceptor.setDaemon(false);
        acceptor.start();
        LOG.info("gateway {} listening on {}:{}{}", config.gatewayId(), config.bindAddress(), port(),
                config.tlsKeystore().isPresent() ? " (TLS)" : "");
    }

    private ServerSocket tlsServerSocket() throws IOException {
        Path ks = config.tlsKeystore().orElseThrow();
        char[] pw = config.tlsKeystorePassword().toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(ks.toString().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12");
            try (InputStream in = Files.newInputStream(ks)) {
                store.load(in, pw);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
            s.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            return s;
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot initialise TLS from keystore " + ks + ": " + e.getMessage(), e);
        }
    }

    /** Bound port (useful when configured with 0). */
    public int port() {
        return serverSocket == null ? config.port() : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!stopping.get()) {
            Socket s;
            try {
                s = serverSocket.accept();
            } catch (SocketException e) {
                if (!stopping.get()) {
                    LOG.warn("accept failed: {}", e.getMessage());
                }
                break;
            } catch (IOException e) {
                LOG.warn("accept failed: {}", e.toString());
                try {
                    Thread.sleep(50); // e.g. too many open files: do not spin
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            open.add(s);
            Runnable handler = handlerFactory.apply(s);
            workers.execute(() -> {
                try {
                    handler.run();
                } finally {
                    open.remove(s);
                }
            });
        }
    }

    /**
     * Graceful shutdown: stop accepting, wait up to {@code graceSeconds} for in-flight statements, then close every
     * client socket.
     */
    public void stop(int graceSeconds) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, graceSeconds));
        while (inFlight.getAsInt() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
        workers.shutdown();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("gateway listener stopped");
    }

    @Override
    public void close() {
        stop(0);
    }

    public int openConnections() {
        return open.size();
    }
}
