package org.dbplatform.proxy.net;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.oracle.OracleConnectionHandler;
import org.dbplatform.proxy.oracle.TnsParseException;
import org.dbplatform.proxy.postgres.PgProtocolException;
import org.dbplatform.proxy.postgres.PostgresConnectionHandler;
import org.dbplatform.proxy.registry.LiveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One bound server socket with its accept loop (a virtual thread). Every accepted socket gets its own
 * virtual thread running the engine's {@link ConnectionHandler}. The route table can be swapped at
 * runtime without touching live connections.
 */
public final class ListenerRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(ListenerRuntime.class);

    private final ProxyRuntime rt;
    private volatile ListenerConfig config;
    private final ServerSocket server;
    private final Thread acceptThread;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean running;

    ListenerRuntime(ProxyRuntime rt, ListenerConfig config) throws IOException {
        this.rt = rt;
        this.config = config;
        this.server = new ServerSocket();
        server.setReuseAddress(true);
        String bind = config.bindAddress() == null || config.bindAddress().isBlank() ? rt.settings().listenAddress() : config.bindAddress();
        try {
            server.bind(new InetSocketAddress(InetAddress.getByName(bind), config.port()), 1024);
        } catch (IOException e) {
            server.close();
            throw new IOException("listener '" + config.name() + "' cannot bind " + bind + ":" + config.port() + ": " + e.getMessage(), e);
        }
        this.acceptThread = Thread.ofVirtual().name("dbp-accept-" + config.name()).unstarted(this::acceptLoop);
    }

    void start() {
        running = true;
        acceptThread.start();
        LOG.info("listener '{}' ({}) listening on {} with {} route(s){}", config.name(), config.engine(),
                server.getLocalSocketAddress(), config.routes().size(), config.defaultRoute() == null ? "" : " + default route");
    }

    public ListenerConfig config() {
        return config;
    }

    void updateConfig(ListenerConfig newConfig) {
        this.config = newConfig;
        LOG.info("listener '{}' routes updated ({} route(s){})", newConfig.name(), newConfig.routes().size(),
                newConfig.defaultRoute() == null ? "" : " + default route");
    }

    public int boundPort() {
        return server.getLocalPort();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public boolean isRunning() {
        return running && !server.isClosed();
    }

    void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        LOG.info("listener '{}' stopped (existing connections keep running)", config.name());
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (SocketException e) {
                if (running) {
                    LOG.warn("listener '{}' accept failed: {}", config.name(), e.getMessage());
                }
                break;
            } catch (IOException e) {
                LOG.warn("listener '{}' accept failed: {}", config.name(), e.getMessage());
                continue;
            }
            dispatch(s);
        }
    }

    private void dispatch(Socket s) {
        ListenerConfig cfg = config;
        rt.metrics().accepted(cfg.name());
        int n = inFlight.incrementAndGet();
        String capReason = n > cfg.maxConnectionsOrDefault()
                ? "listener '" + cfg.name() + "' connection cap reached (" + cfg.maxConnectionsOrDefault() + ")" : null;
        LiveConnection live = new LiveConnection(rt.registry().nextId(), cfg.name(), cfg.engine(),
                s.getInetAddress().getHostAddress(), s.getPort());
        Thread.ofVirtual().name("dbp-conn-" + live.id()).start(() -> serve(s, live, capReason));
    }

    private void serve(Socket s, LiveConnection live, String capReason) {
        ConnectionContext ctx = null;
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            ctx = new ConnectionContext(rt, this, s, live, capReason);
            handlerFor(ctx.config()).handle(ctx);
        } catch (IOException e) {
            LOG.debug("{} ended with I/O error: {}", live.id(), e.toString());
            if (ctx != null) {
                ctx.closed("I/O error: " + e.getMessage());
            }
        } catch (TnsParseException | PgProtocolException e) {
            // not a database client (port scanner, HTTP probe, wrong port): one line, no stack trace
            LOG.info("{} from {}:{} rejected: {}", live.id(), live.clientAddr(), live.clientPort(), e.getMessage());
            rt.metrics().refused(live.listener(), "protocol");
            if (ctx != null) {
                ctx.closed("protocol error: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.warn("{} ended with error: {}", live.id(), e.toString(), e);
            if (ctx != null) {
                ctx.closed("error: " + e.getMessage());
            }
        } finally {
            inFlight.decrementAndGet();
            if (ctx != null) {
                ctx.close();
            } else {
                ConnectionContext.closeQuietly(s);
            }
        }
    }

    private static ConnectionHandler handlerFor(ListenerConfig cfg) {
        return switch (cfg.engine()) {
            case ORACLE -> new OracleConnectionHandler();
            case POSTGRES -> new PostgresConnectionHandler();
            case MSSQL, TCP -> new PassThroughHandler();
        };
    }
}
