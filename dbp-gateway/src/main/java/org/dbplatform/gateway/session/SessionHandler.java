package org.dbplatform.gateway.session;

import org.dbplatform.gateway.GatewayConfig;
import org.dbplatform.gateway.Version;
import org.dbplatform.gateway.control.AuthException;
import org.dbplatform.gateway.control.Resolver;
import org.dbplatform.gateway.control.SessionResolution;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.pool.PoolManager;
import org.dbplatform.gateway.telemetry.GatewayMetrics;
import org.dbplatform.gateway.telemetry.GatewayTelemetry;
import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.messages.Close;
import org.dbplatform.protocol.messages.CloseCursor;
import org.dbplatform.protocol.messages.CloseStatement;
import org.dbplatform.protocol.messages.Commit;
import org.dbplatform.protocol.messages.ErrorMessage;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteBatch;
import org.dbplatform.protocol.messages.Fetch;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.HelloOk;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Messages;
import org.dbplatform.protocol.messages.Metadata;
import org.dbplatform.protocol.messages.Ok;
import org.dbplatform.protocol.messages.Ping;
import org.dbplatform.protocol.messages.Pong;
import org.dbplatform.protocol.messages.Prepare;
import org.dbplatform.protocol.messages.Prepared;
import org.dbplatform.protocol.messages.ReleaseSavepoint;
import org.dbplatform.protocol.messages.Rollback;
import org.dbplatform.protocol.messages.SavepointSet;
import org.dbplatform.protocol.messages.SetAutoCommit;
import org.dbplatform.protocol.messages.SetCatalog;
import org.dbplatform.protocol.messages.SetClientInfo;
import org.dbplatform.protocol.messages.SetNetworkTimeout;
import org.dbplatform.protocol.messages.SetReadOnly;
import org.dbplatform.protocol.messages.SetSavepoint;
import org.dbplatform.protocol.messages.SetSchema;
import org.dbplatform.protocol.messages.SetTransactionIsolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * One TCP connection = one logical session. Reads a frame, dispatches it, writes the terminal response; protocol
 * violations end the session with a fatal ERROR.
 */
public final class SessionHandler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);
    private static final int BUFFER = 64 * 1024;
    /** A HELLO never needs more than this: unauthenticated peers cannot make the gateway allocate a 64 MiB frame. */
    static final int MAX_HELLO_FRAME_BYTES = 256 * 1024;
    /** Unauthenticated connections must say HELLO within this time (seconds), whatever the idle timeout is. */
    static final int HELLO_TIMEOUT_SECONDS = 15;

    private final Socket socket;
    private final GatewayConfig config;
    private final Resolver resolver;
    private final PoolManager pools;
    private final SessionRegistry registry;
    private final GatewayTelemetry telemetry;
    private final GatewayMetrics metrics;
    private final Executor networkTimeoutExecutor;
    private final BooleanSupplier stopping;
    private final int listenPort;

    private FrameWriter out;
    private LogicalSession session;

    public SessionHandler(Socket socket, GatewayConfig config, int listenPort, Resolver resolver, PoolManager pools,
                          SessionRegistry registry, GatewayTelemetry telemetry, GatewayMetrics metrics,
                          Executor networkTimeoutExecutor, BooleanSupplier stopping) {
        this.socket = socket;
        this.config = config;
        this.listenPort = listenPort;
        this.resolver = resolver;
        this.pools = pools;
        this.registry = registry;
        this.telemetry = telemetry;
        this.metrics = metrics;
        this.networkTimeoutExecutor = networkTimeoutExecutor;
        this.stopping = stopping;
    }

    public LogicalSession session() {
        return session;
    }

    @Override
    public void run() {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        try {
            socket.setTcpNoDelay(true);
            int idleMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(0, config.idleTimeoutSeconds()) * 1000L);
            socket.setSoTimeout(idleMillis > 0 ? Math.min(idleMillis, HELLO_TIMEOUT_SECONDS * 1000) : HELLO_TIMEOUT_SECONDS * 1000);
            BufferedInputStream buffered = new BufferedInputStream(socket.getInputStream(), BUFFER);
            FrameReader in = new FrameReader(buffered, config.maxFrameBytes());
            out = new FrameWriter(new BufferedOutputStream(socket.getOutputStream(), BUFFER), config.maxFrameBytes());

            // the first frame is read with a small limit: nothing is allocated for an unauthenticated peer beyond it
            Frame first = new FrameReader(buffered, Math.min(config.maxFrameBytes(), MAX_HELLO_FRAME_BYTES)).readFrame();
            if (first.type() != MessageType.HELLO) {
                sendFatal(ErrorMessage.STATE_GENERAL, "protocol violation: expected HELLO, got " + first.type());
                return;
            }
            Hello hello = (Hello) Messages.decode(first);
            if (!handshake(hello, peer)) {
                return;
            }
            socket.setSoTimeout(idleMillis);
            StatementExecutor executor = new StatementExecutor(session, out, config.rowsFrameSoftBytes(), telemetry);
            while (!session.isClosed()) {
                Frame frame = in.readFrame();
                Message m = Messages.decode(frame);
                if (!dispatch(m, executor)) {
                    break;
                }
            }
        } catch (EOFException e) {
            LOG.debug("{}: client closed the connection", peer);
        } catch (SocketTimeoutException e) {
            LOG.info("{}: idle for more than {} s, closing session {}", peer, config.idleTimeoutSeconds(),
                    session != null ? session.id() : "-");
            sendQuietly(ErrorMessage.fatal(ErrorMessage.STATE_CONNECTION_FAILURE, "idle timeout"));
        } catch (ProtocolException e) {
            LOG.warn("{}: protocol violation: {}", peer, e.getMessage());
            sendQuietly(ErrorMessage.fatal(ErrorMessage.STATE_GENERAL, "protocol violation: " + e.getMessage()));
        } catch (SocketException e) {
            LOG.debug("{}: socket closed: {}", peer, e.getMessage());
        } catch (IOException e) {
            LOG.debug("{}: I/O error: {}", peer, e.toString());
        } catch (RuntimeException e) {
            LOG.error("{}: unexpected error in session {}", peer, session != null ? session.id() : "-", e);
            sendQuietly(ErrorMessage.fatal(ErrorMessage.STATE_GENERAL, "internal gateway error: " + e));
        } finally {
            closeSession();
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    // ------------------------------------------------------------------ HELLO

    private boolean handshake(Hello hello, String peer) throws IOException {
        if (hello.protocolVersion() != ProtocolConstants.VERSION) {
            sendFatal(ErrorMessage.STATE_REJECTED, "unsupported protocol version " + hello.protocolVersion());
            return false;
        }
        Map<String, String> props = hello.properties();
        String datasource = props.get(Hello.PROP_DATASOURCE);
        if (datasource == null || datasource.isBlank()) {
            sendFatal(ErrorMessage.STATE_REJECTED, "HELLO property 'datasource' is required");
            return false;
        }
        if (stopping.getAsBoolean()) {
            sendFatal(ErrorMessage.STATE_CONNECTION_FAILURE, "gateway is shutting down");
            return false;
        }
        String apiKey = props.get(Hello.PROP_API_KEY);
        String application = props.get(Hello.PROP_APPLICATION);
        String user = props.get(Hello.PROP_USER);
        SessionResolution res;
        try {
            res = resolver.resolve(datasource, apiKey, application, user);
        } catch (AuthException e) {
            LOG.info("{}: HELLO rejected for datasource '{}': {}", peer, datasource, e.getMessage());
            metrics.recordError(e.sqlState());
            sendFatal(e.sqlState(), e.getMessage());
            return false;
        }
        LogicalSession s = new LogicalSession(registry.nextSessionId(), res, resolver, pools, apiKey, application, user,
                config.maxOpenCursorsPerSession(), networkTimeoutExecutor, peer);
        applyHelloProperties(s, props);
        try {
            registry.register(s, res);
        } catch (AuthException e) {
            LOG.info("{}: HELLO rejected: {}", peer, e.getMessage());
            metrics.recordError(e.sqlState());
            sendFatal(e.sqlState(), e.getMessage());
            return false;
        }
        PhysicalPool pool;
        Map<String, String> serverProps;
        try {
            pool = pools.poolFor(res.datasource().settings(), () -> {
                try {
                    return resolver.credentials(res.datasource());
                } catch (AuthException e) {
                    throw new SQLException(e.getMessage(), e.sqlState(), e);
                }
            });
            serverProps = new LinkedHashMap<>(pool.serverProperties());
        } catch (SQLException e) {
            registry.unregister(s);
            LOG.warn("{}: HELLO for datasource '{}' failed: {}", peer, datasource, e.getMessage());
            metrics.recordError(e.getSQLState());
            sendFatal(e.getSQLState() != null ? e.getSQLState() : ErrorMessage.STATE_CONNECTION_UNABLE, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // e.g. an unresolvable passwordEnv in static mode: the slot reserved in the registry must not leak
            registry.unregister(s);
            LOG.warn("{}: HELLO for datasource '{}' failed: {}", peer, datasource, e.toString());
            metrics.recordError(ErrorMessage.STATE_CONNECTION_UNABLE);
            sendFatal(ErrorMessage.STATE_CONNECTION_UNABLE, "cannot open datasource '" + datasource + "': " + e.getMessage());
            return false;
        }
        s.helloPool(pool);
        metrics.datasourceSeen(datasource);
        serverProps.put(HelloOk.PROP_URL, "jdbc:dbp://" + config.advertisedHost() + ":" + listenPort + "/" + datasource);
        serverProps.put(HelloOk.PROP_POOL_MODE, res.poolMode().name());
        session = s;
        Messages.write(out, new HelloOk(s.id(), Version.CURRENT, pool.engineName(), serverProps));
        LOG.debug("{}: session {} opened for {} on {} ({})", peer, s.id(), res.identity().application(), datasource,
                res.poolMode());
        return true;
    }

    private static void applyHelloProperties(LogicalSession s, Map<String, String> props) {
        SessionSettings st = s.settings();
        String ac = props.get(Hello.PROP_AUTOCOMMIT);
        if (ac != null) {
            st.autoCommit(Boolean.parseBoolean(ac.trim()));
        }
        String ro = props.get(Hello.PROP_READ_ONLY);
        if (ro != null) {
            st.readOnly(Boolean.parseBoolean(ro.trim()));
        }
        String schema = props.get(Hello.PROP_SCHEMA);
        if (schema != null && !schema.isBlank()) {
            st.schema(schema);
        }
        String iso = props.get(Hello.PROP_TX_ISOLATION);
        if (iso != null && !iso.isBlank()) {
            try {
                st.transactionIsolation(Integer.parseInt(iso.trim()));
            } catch (NumberFormatException ignored) {
                // unknown value: keep the driver default
            }
        }
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (e.getKey().startsWith(Hello.PROP_CLIENT_INFO_PREFIX) && e.getValue() != null) {
                st.clientInfo(e.getKey().substring(Hello.PROP_CLIENT_INFO_PREFIX.length()), e.getValue());
            }
        }
    }

    // ------------------------------------------------------------------ dispatch

    /** Handles one request; returns {@code false} when the session must end. */
    private boolean dispatch(Message m, StatementExecutor executor) throws IOException {
        session.touch();
        session.executing(true);
        try {
            switch (m) {
                case Ping p -> Messages.write(out, new Pong());
                case Close c -> {
                    Messages.write(out, new Ok());
                    return false;
                }
                case Hello h -> Messages.write(out, ErrorMessage.of(ErrorMessage.STATE_GENERAL, "session already established"));
                case Prepare p -> {
                    int id = session.prepare(p.sql(), p.kind(), p.autoGeneratedKeys(), p.generatedKeyColumns());
                    Messages.write(out, new Prepared(id, ProtocolConstants.UNKNOWN_PARAMETER_COUNT));
                }
                case Execute e -> executor.execute(e);
                case Fetch f -> executor.fetch(f);
                case CloseCursor cc -> {
                    session.closeCursor(cc.cursorId());
                    Messages.write(out, new Ok());
                }
                case CloseStatement cs -> {
                    session.closeStatement(cs.statementId());
                    Messages.write(out, new Ok());
                }
                case ExecuteBatch b -> executor.executeBatch(b);
                case SetAutoCommit a -> {
                    session.setAutoCommit(a.autoCommit());
                    Messages.write(out, new Ok());
                }
                case Commit c -> {
                    session.commit();
                    Messages.write(out, new Ok());
                }
                case Rollback r -> {
                    session.rollback(r.savepointName());
                    Messages.write(out, new Ok());
                }
                case SetSavepoint s -> Messages.write(out, new SavepointSet(session.setSavepoint(s.name())));
                case ReleaseSavepoint r -> {
                    session.releaseSavepoint(r.name());
                    Messages.write(out, new Ok());
                }
                case SetTransactionIsolation t -> {
                    session.setTransactionIsolation(t.level());
                    Messages.write(out, new Ok());
                }
                case SetReadOnly r -> {
                    session.setReadOnly(r.readOnly());
                    Messages.write(out, new Ok());
                }
                case SetSchema s -> {
                    session.setSchema(s.schema());
                    Messages.write(out, new Ok());
                }
                case SetCatalog c -> {
                    session.setCatalog(c.catalog());
                    Messages.write(out, new Ok());
                }
                case SetClientInfo ci -> {
                    session.setClientInfo(ci.name(), ci.value());
                    Messages.write(out, new Ok());
                }
                case SetNetworkTimeout nt -> {
                    session.setNetworkTimeout(nt.millis());
                    Messages.write(out, new Ok());
                }
                case Metadata md -> executor.metadata(md);
                default -> {
                    sendFatal(ErrorMessage.STATE_GENERAL, "protocol violation: unexpected message " + m.type());
                    return false;
                }
            }
        } catch (SQLException e) {
            boolean stateLost = session.onSqlFailure(e);
            if (stateLost) {
                metrics.recordError(ErrorMessage.STATE_CONNECTION_FAILURE);
                sendFatal(ErrorMessage.STATE_CONNECTION_FAILURE, "physical connection lost: " + e.getMessage());
                return false;
            }
            ErrorMessage err = toError(e);
            LOG.debug("session {}: {} {}", session.id(), err.sqlState(), err.message());
            metrics.recordError(err.sqlState());
            Messages.write(out, err);
        } catch (ProtocolException e) {
            // oversize outgoing frame etc.: the request failed but the connection is intact
            LOG.warn("session {}: {}", session.id(), e.getMessage());
            metrics.recordError(ErrorMessage.STATE_GENERAL);
            Messages.write(out, ErrorMessage.of(ErrorMessage.STATE_GENERAL, e.getMessage()));
        } catch (RuntimeException e) {
            LOG.error("session {}: internal error handling {}", session.id(), m.type(), e);
            metrics.recordError(ErrorMessage.STATE_GENERAL);
            Messages.write(out, ErrorMessage.of(ErrorMessage.STATE_GENERAL, "internal gateway error: " + e));
        } finally {
            session.executing(false);
        }
        return true;
    }

    static ErrorMessage toError(SQLException e) {
        String state = e.getSQLState();
        if (state == null || state.isBlank()) {
            state = e instanceof java.sql.SQLTimeoutException ? ErrorMessage.STATE_TIMEOUT
                    : e instanceof java.sql.SQLFeatureNotSupportedException ? ErrorMessage.STATE_NOT_SUPPORTED
                    : ErrorMessage.STATE_GENERAL;
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new ErrorMessage(state, e.getErrorCode(), msg, false);
    }

    private void sendFatal(String sqlState, String message) throws IOException {
        Messages.write(out, ErrorMessage.fatal(sqlState, message));
    }

    private void sendQuietly(ErrorMessage err) {
        if (out == null) {
            return;
        }
        try {
            Messages.write(out, err);
        } catch (IOException | RuntimeException ignored) {
            // the peer is probably gone
        }
    }

    private void closeSession() {
        LogicalSession s = session;
        if (s == null) {
            return;
        }
        try {
            s.close();
        } finally {
            registry.unregister(s);
            session = null;
        }
    }
}
