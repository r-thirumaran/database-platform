package org.dbplatform.jdbc;

import org.dbplatform.jdbc.transport.Transport;
import org.dbplatform.protocol.JdbcUrl;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.messages.Close;
import org.dbplatform.protocol.messages.CloseCursor;
import org.dbplatform.protocol.messages.Commit;
import org.dbplatform.protocol.messages.ErrorMessage;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.HelloOk;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Ok;
import org.dbplatform.protocol.messages.Ping;
import org.dbplatform.protocol.messages.Pong;
import org.dbplatform.protocol.messages.ReleaseSavepoint;
import org.dbplatform.protocol.messages.ResultSetHeader;
import org.dbplatform.protocol.messages.Rollback;
import org.dbplatform.protocol.messages.Rows;
import org.dbplatform.protocol.messages.SavepointSet;
import org.dbplatform.protocol.messages.SetAutoCommit;
import org.dbplatform.protocol.messages.SetCatalog;
import org.dbplatform.protocol.messages.SetClientInfo;
import org.dbplatform.protocol.messages.SetNetworkTimeout;
import org.dbplatform.protocol.messages.SetReadOnly;
import org.dbplatform.protocol.messages.SetSavepoint;
import org.dbplatform.protocol.messages.SetSchema;
import org.dbplatform.protocol.messages.SetTransactionIsolation;
import org.dbplatform.protocol.messages.StatementKind;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A logical JDBC connection: one {@link Transport} (socket) and one gateway session established with HELLO.
 *
 * <p>Result-set type {@code TYPE_SCROLL_INSENSITIVE} and holdability {@code HOLD_CURSORS_OVER_COMMIT} are
 * downgraded to forward-only / close-at-commit with a warning so that framework defaults keep working;
 * {@code TYPE_SCROLL_SENSITIVE} and {@code CONCUR_UPDATABLE} are rejected ({@code 0A000}).</p>
 */
public final class DbpConnection extends DbpWrapper implements Connection {

    private static final Logger LOG = Logger.getLogger("org.dbplatform.jdbc");
    private static final String STATE_WARNING_DOWNGRADE = "01S02";

    private final JdbcUrl url;
    private final Transport transport;
    private final HelloOk hello;
    private final String user;
    private final int defaultFetchSize;
    private final DbpWarningChain warnings = new DbpWarningChain();
    private final Set<DbpStatement> statements = new LinkedHashSet<>();
    private final Properties clientInfo = new Properties();

    private volatile boolean closed;
    private boolean autoCommit;
    private boolean readOnly;
    private int isolation;
    private String schema;
    private String catalog;
    private int networkTimeout;
    private int savepointCounter;
    private DbpDatabaseMetaData metaData;

    private DbpConnection(JdbcUrl url, Transport transport, HelloOk hello, String user, int defaultFetchSize,
                          boolean autoCommit, boolean readOnly, int isolation, String schema, Map<String, String> clientInfo,
                          int networkTimeout) {
        this.url = url;
        this.transport = transport;
        this.hello = hello;
        this.user = user;
        this.defaultFetchSize = defaultFetchSize;
        this.autoCommit = autoCommit;
        this.readOnly = readOnly;
        this.isolation = isolation;
        this.schema = schema;
        this.networkTimeout = networkTimeout;
        this.clientInfo.putAll(clientInfo);
    }

    // ---------------------------------------------------------------- connect

    /**
     * Connects and performs the HELLO handshake.
     *
     * @param url  parsed URL
     * @param info connection properties ({@code Properties} argument of {@code Driver.connect}); URL values win
     * @return the open connection
     * @throws SQLException on connect, handshake or property errors
     */
    static DbpConnection open(JdbcUrl url, Properties info) throws SQLException {
        Map<String, String> props = url.mergedProperties(info);
        boolean ssl = Boolean.parseBoolean(props.getOrDefault(DbpDriver.PROP_SSL, "false"));
        int connectTimeout = intProperty(props, DbpDriver.PROP_CONNECT_TIMEOUT_MS, DbpDriver.DEFAULT_CONNECT_TIMEOUT_MS);
        int socketTimeout = intProperty(props, DbpDriver.PROP_SOCKET_TIMEOUT_MS, 0);
        int fetchSize = intProperty(props, DbpDriver.PROP_FETCH_SIZE, ProtocolConstants.DEFAULT_FETCH_SIZE);
        int maxFrameBytes = intProperty(props, ProtocolConstants.PROP_MAX_FRAME_BYTES, ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
        if (fetchSize <= 0) {
            fetchSize = ProtocolConstants.DEFAULT_FETCH_SIZE;
        }
        if (maxFrameBytes < 1) {
            throw invalidProperty(ProtocolConstants.PROP_MAX_FRAME_BYTES, props.get(ProtocolConstants.PROP_MAX_FRAME_BYTES));
        }

        String apiKey = blankToNull(props.get(DbpDriver.PROP_API_KEY));
        if (apiKey == null) {
            apiKey = blankToNull(props.get(DbpDriver.PROP_PASSWORD));
        }
        String user = blankToNull(props.get(DbpDriver.PROP_USER));
        boolean autoCommit = Boolean.parseBoolean(props.getOrDefault(DbpDriver.PROP_AUTO_COMMIT, "true"));
        String readOnlyText = blankToNull(props.get(DbpDriver.PROP_READ_ONLY));
        String schema = blankToNull(props.get(DbpDriver.PROP_SCHEMA));
        String txIsolation = blankToNull(props.get(DbpDriver.PROP_TX_ISOLATION));

        Map<String, String> helloProps = new LinkedHashMap<>();
        helloProps.put(Hello.PROP_DATASOURCE, url.datasource());
        if (apiKey != null) {
            helloProps.put(Hello.PROP_API_KEY, apiKey);
        }
        String application = blankToNull(props.get(DbpDriver.PROP_APPLICATION));
        if (application != null) {
            helloProps.put(Hello.PROP_APPLICATION, application);
        }
        if (user != null) {
            helloProps.put(Hello.PROP_USER, user);
        }
        helloProps.put(Hello.PROP_AUTOCOMMIT, Boolean.toString(autoCommit));
        if (readOnlyText != null) {
            helloProps.put(Hello.PROP_READ_ONLY, Boolean.toString(Boolean.parseBoolean(readOnlyText)));
        }
        if (schema != null) {
            helloProps.put(Hello.PROP_SCHEMA, schema);
        }
        Map<String, String> clientInfo = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (e.getKey().startsWith(Hello.PROP_CLIENT_INFO_PREFIX) && e.getValue() != null) {
                helloProps.put(e.getKey(), e.getValue());
                clientInfo.put(e.getKey().substring(Hello.PROP_CLIENT_INFO_PREFIX.length()), e.getValue());
            }
        }
        int isolation = -1;
        if (txIsolation != null) {
            isolation = parseIsolation(txIsolation);
            helloProps.put(Hello.PROP_TX_ISOLATION, Integer.toString(isolation));
        }

        Transport transport = Transport.connect(url.hosts(), ssl, connectTimeout, socketTimeout, maxFrameBytes);
        HelloOk ok;
        try {
            List<Message> replies = transport.call(Hello.of(DriverVersion.CLIENT_NAME, DriverVersion.VERSION, helloProps), -1);
            Message m = replies.get(replies.size() - 1);
            if (m instanceof ErrorMessage err) {
                throw err.toSqlException();
            }
            if (!(m instanceof HelloOk helloOk)) {
                throw DbpSqlExceptions.protocolViolation("expected HELLO_OK but got " + m.type());
            }
            ok = helloOk;
        } catch (SQLException | RuntimeException e) {
            transport.close();
            throw e;
        }
        if (isolation < 0) {
            isolation = ok.intProperty("defaultTransactionIsolation", Connection.TRANSACTION_READ_COMMITTED);
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("session " + ok.sessionId() + " opened on " + transport.endpoint() + " (engine " + ok.engine() + ")");
        }
        return new DbpConnection(url, transport, ok, user, fetchSize, autoCommit,
                readOnlyText != null && Boolean.parseBoolean(readOnlyText), isolation, schema, clientInfo, socketTimeout);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int intProperty(Map<String, String> props, String name, int dflt) throws SQLException {
        String v = blankToNull(props.get(name));
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw invalidProperty(name, v);
        }
    }

    private static SQLException invalidProperty(String name, String value) {
        return new SQLNonTransientConnectionException("invalid value '" + value + "' for connection property " + name,
                Transport.STATE_CONNECT_FAILED);
    }

    private static int parseIsolation(String text) throws SQLException {
        try {
            return checkIsolation(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return switch (text.trim().toUpperCase()) {
                case "READ_UNCOMMITTED", "TRANSACTION_READ_UNCOMMITTED" -> TRANSACTION_READ_UNCOMMITTED;
                case "READ_COMMITTED", "TRANSACTION_READ_COMMITTED" -> TRANSACTION_READ_COMMITTED;
                case "REPEATABLE_READ", "TRANSACTION_REPEATABLE_READ" -> TRANSACTION_REPEATABLE_READ;
                case "SERIALIZABLE", "TRANSACTION_SERIALIZABLE" -> TRANSACTION_SERIALIZABLE;
                default -> throw invalidProperty(DbpDriver.PROP_TX_ISOLATION, text);
            };
        }
    }

    private static int checkIsolation(int level) throws SQLException {
        return switch (level) {
            case TRANSACTION_READ_UNCOMMITTED, TRANSACTION_READ_COMMITTED, TRANSACTION_REPEATABLE_READ,
                 TRANSACTION_SERIALIZABLE -> level;
            default -> throw DbpSqlExceptions.invalidArgument("invalid transaction isolation level " + level);
        };
    }

    // ---------------------------------------------------------------- wire helpers

    /**
     * Sends a request and returns the frames received (terminal frame last). An ERROR terminal frame is
     * converted to the matching {@link SQLException}; a fatal one also closes the connection.
     */
    List<Message> exchange(Message request, int columnCountForRows) throws SQLException {
        checkOpen();
        List<Message> replies;
        try {
            replies = transport.call(request, columnCountForRows);
        } catch (SQLException e) {
            if (transport.isClosed()) {
                markClosed(e);
            }
            throw e;
        }
        Message last = replies.get(replies.size() - 1);
        if (last instanceof ErrorMessage err) {
            if (!err.fatal()) {
                closeOrphanedCursors(replies);
            }
            throw DbpSqlExceptions.fromError(err, this);
        }
        return replies;
    }

    /**
     * An ERROR that terminates an EXECUTE sequence after result items were streamed leaves those cursors open on
     * the gateway (and the session pinned) unless they are closed: the application never sees them.
     */
    private void closeOrphanedCursors(List<Message> replies) {
        for (int i = 0; i + 1 < replies.size(); i++) {
            if (replies.get(i) instanceof ResultSetHeader h && replies.get(i + 1) instanceof Rows rows
                    && rows.cursorId() == h.cursorId() && !rows.last()) {
                try {
                    transport.call(new CloseCursor(h.cursorId()), -1);
                } catch (SQLException e) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("CLOSE_CURSOR " + h.cursorId() + " after failed EXECUTE: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    Message exchangeTerminal(Message request) throws SQLException {
        List<Message> replies = exchange(request, -1);
        return replies.get(replies.size() - 1);
    }

    void exchangeOk(Message request) throws SQLException {
        Message m = exchangeTerminal(request);
        if (!(m instanceof Ok)) {
            throw DbpSqlExceptions.protocolViolation("expected OK in response to " + request.type() + " but got " + m.type());
        }
    }

    /** Marks the connection closed after a fatal error; statements and result sets are closed locally. */
    void markClosed(SQLException cause) {
        if (closed) {
            return;
        }
        closed = true;
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("connection " + hello.sessionId() + " closed after failure: " + cause.getMessage());
        }
        transport.close();
        closeStatementsLocally();
    }

    private void closeStatementsLocally() {
        List<DbpStatement> copy;
        synchronized (statements) {
            copy = List.copyOf(statements);
            statements.clear();
        }
        for (DbpStatement s : copy) {
            s.closeLocally();
        }
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw DbpSqlExceptions.connectionClosed();
        }
        if (transport.isClosed()) {
            markClosed(DbpSqlExceptions.connectionClosed());
            throw DbpSqlExceptions.connectionClosed();
        }
    }

    int defaultFetchSize() {
        return defaultFetchSize;
    }

    HelloOk hello() {
        return hello;
    }

    String user() {
        return user;
    }

    JdbcUrl url() {
        return url;
    }

    /** The session id assigned by the gateway (useful for support / correlation with gateway telemetry). */
    public String getSessionId() {
        return hello.sessionId();
    }

    void unregisterStatement(DbpStatement statement) {
        synchronized (statements) {
            statements.remove(statement);
        }
    }

    private <T extends DbpStatement> T register(T statement) {
        synchronized (statements) {
            statements.add(statement);
        }
        return statement;
    }

    // ---------------------------------------------------------------- statements

    private int checkResultSetType(int resultSetType) throws SQLException {
        return switch (resultSetType) {
            case ResultSet.TYPE_FORWARD_ONLY -> resultSetType;
            case ResultSet.TYPE_SCROLL_INSENSITIVE -> {
                warnings.add("TYPE_SCROLL_INSENSITIVE is not supported; result set downgraded to TYPE_FORWARD_ONLY",
                        STATE_WARNING_DOWNGRADE);
                yield ResultSet.TYPE_FORWARD_ONLY;
            }
            case ResultSet.TYPE_SCROLL_SENSITIVE -> throw notSupported("TYPE_SCROLL_SENSITIVE result sets");
            default -> throw DbpSqlExceptions.invalidArgument("invalid result set type " + resultSetType);
        };
    }

    private int checkConcurrency(int concurrency) throws SQLException {
        return switch (concurrency) {
            case ResultSet.CONCUR_READ_ONLY -> concurrency;
            case ResultSet.CONCUR_UPDATABLE -> throw notSupported("CONCUR_UPDATABLE result sets");
            default -> throw DbpSqlExceptions.invalidArgument("invalid result set concurrency " + concurrency);
        };
    }

    private int checkHoldability(int holdability) throws SQLException {
        return switch (holdability) {
            case ResultSet.CLOSE_CURSORS_AT_COMMIT -> holdability;
            case ResultSet.HOLD_CURSORS_OVER_COMMIT -> {
                warnings.add("HOLD_CURSORS_OVER_COMMIT is not negotiable; holdability downgraded to CLOSE_CURSORS_AT_COMMIT",
                        STATE_WARNING_DOWNGRADE);
                yield ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
            default -> throw DbpSqlExceptions.invalidArgument("invalid result set holdability " + holdability);
        };
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkOpen();
        return register(new DbpStatement(this, checkResultSetType(resultSetType), checkConcurrency(resultSetConcurrency),
                checkHoldability(resultSetHoldability)));
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, Statement.NO_GENERATED_KEYS);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();
        return register(new DbpPreparedStatement(this, sql, StatementKind.PREPARED, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT, DbpStatement.checkKeysFlag(autoGeneratedKeys),
                List.of()));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();
        throw notSupported("generated key column indexes (use prepareStatement(sql, String[] columnNames))");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        return register(new DbpPreparedStatement(this, sql, StatementKind.PREPARED, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT, Statement.RETURN_GENERATED_KEYS,
                DbpStatement.keyColumns(columnNames)));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        checkOpen();
        return register(new DbpPreparedStatement(this, sql, StatementKind.PREPARED, checkResultSetType(resultSetType),
                checkConcurrency(resultSetConcurrency), checkHoldability(resultSetHoldability),
                Statement.NO_GENERATED_KEYS, List.of()));
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql, resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkOpen();
        return register(new DbpCallableStatement(this, sql, checkResultSetType(resultSetType),
                checkConcurrency(resultSetConcurrency), checkHoldability(resultSetHoldability)));
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        return sql;
    }

    // ---------------------------------------------------------------- transactions

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (this.autoCommit == autoCommit) {
            return;
        }
        exchangeOk(new SetAutoCommit(autoCommit));
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        exchangeOk(Commit.INSTANCE);
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        exchangeOk(Rollback.FULL);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        checkOpen();
        exchangeOk(new Rollback(savepointName(savepoint)));
    }

    private static String savepointName(Savepoint savepoint) throws SQLException {
        if (savepoint instanceof DbpSavepoint sp) {
            return sp.wireName();
        }
        throw DbpSqlExceptions.invalidArgument("savepoint was not created by this driver: " + savepoint);
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return createSavepoint(null);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        if (name == null) {
            throw DbpSqlExceptions.invalidArgument("savepoint name must not be null");
        }
        return createSavepoint(name);
    }

    private Savepoint createSavepoint(String name) throws SQLException {
        checkOpen();
        Message m = exchangeTerminal(new SetSavepoint(name));
        if (!(m instanceof SavepointSet set)) {
            throw DbpSqlExceptions.protocolViolation("expected SAVEPOINT_SET but got " + m.type());
        }
        String wireName = set.name() != null ? set.name() : name;
        if (wireName == null) {
            throw DbpSqlExceptions.protocolViolation("SAVEPOINT_SET without a name for an unnamed savepoint");
        }
        int id;
        synchronized (this) {
            id = ++savepointCounter;
        }
        return new DbpSavepoint(wireName, name != null, id);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        checkOpen();
        exchangeOk(new ReleaseSavepoint(savepointName(savepoint)));
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        if (level == TRANSACTION_NONE) {
            throw DbpSqlExceptions.invalidArgument("TRANSACTION_NONE cannot be set");
        }
        checkIsolation(level);
        exchangeOk(new SetTransactionIsolation(level));
        isolation = level;
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return isolation;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
        exchangeOk(new SetReadOnly(readOnly));
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
    }

    // ---------------------------------------------------------------- session settings

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
        exchangeOk(new SetCatalog(catalog));
        this.catalog = catalog;
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return catalog;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
        exchangeOk(new SetSchema(schema));
        this.schema = schema;
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return schema;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (name == null) {
            throw new SQLClientInfoException("client info name must not be null", DbpSqlExceptions.STATE_INVALID_ARGUMENT,
                    Map.of());
        }
        try {
            checkOpen();
            exchangeOk(new SetClientInfo(name, value));
        } catch (SQLException e) {
            SQLClientInfoException ex = new SQLClientInfoException(e.getMessage(), e.getSQLState(), e.getErrorCode(),
                    Map.of(name, ClientInfoStatus.REASON_UNKNOWN), e);
            throw ex;
        }
        if (value == null) {
            clientInfo.remove(name);
        } else {
            clientInfo.setProperty(name, value);
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (properties == null) {
            return;
        }
        Map<String, ClientInfoStatus> failures = new HashMap<>();
        SQLException first = null;
        for (String name : properties.stringPropertyNames()) {
            try {
                setClientInfo(name, properties.getProperty(name));
            } catch (SQLClientInfoException e) {
                failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw new SQLClientInfoException(first.getMessage(), first.getSQLState(), first.getErrorCode(), failures, first);
        }
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        return clientInfo.getProperty(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        Properties copy = new Properties();
        copy.putAll(clientInfo);
        return copy;
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkOpen();
        if (milliseconds < 0) {
            throw DbpSqlExceptions.invalidArgument("network timeout must be >= 0");
        }
        exchangeOk(new SetNetworkTimeout(milliseconds));
        transport.setSocketTimeout(milliseconds);
        networkTimeout = milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeout;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw DbpSqlExceptions.invalidArgument("timeout must be >= 0");
        }
        if (closed || transport.isClosed()) {
            return false;
        }
        transport.lock().lock();
        try {
            int previous = transport.getSocketTimeout();
            try {
                if (timeout > 0) {
                    transport.setSocketTimeout((int) Math.min(Integer.MAX_VALUE, timeout * 1000L));
                }
                return exchangeTerminal(Ping.INSTANCE) instanceof Pong;
            } catch (SQLException e) {
                return false;
            } finally {
                if (!transport.isClosed()) {
                    try {
                        transport.setSocketTimeout(previous);
                    } catch (SQLException ignored) {
                        // the socket is gone; isValid already reported false or will on the next call
                    }
                }
            }
        } finally {
            transport.lock().unlock();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        transport.lock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            closeStatementsLocally();
            if (!transport.isClosed()) {
                try {
                    transport.call(Close.INSTANCE, -1);
                } catch (SQLException e) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("CLOSE not acknowledged by gateway: " + e.getMessage());
                    }
                }
            }
        } finally {
            transport.close();
            transport.lock().unlock();
        }
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        Runnable r = () -> {
            transport.close();
            closeStatementsLocally();
        };
        if (executor != null) {
            executor.execute(r);
        } else {
            r.run();
        }
    }

    @Override
    public boolean isClosed() {
        if (!closed && transport.isClosed()) {
            markClosed(DbpSqlExceptions.connectionClosed());
        }
        return closed;
    }

    // ---------------------------------------------------------------- metadata, warnings, misc

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        if (metaData == null) {
            metaData = new DbpDatabaseMetaData(this);
        }
        return metaData;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings.get();
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings.clear();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return new HashMap<>();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkOpen();
        if (map != null && !map.isEmpty()) {
            throw notSupported("custom type maps");
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        checkHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Clob createClob() throws SQLException {
        checkOpen();
        return new DbpClob("");
    }

    @Override
    public Blob createBlob() throws SQLException {
        checkOpen();
        return new DbpBlob(new byte[0]);
    }

    @Override
    public NClob createNClob() throws SQLException {
        checkOpen();
        return new DbpClob("");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw notSupported("createSQLXML (pass XML as a String)");
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw notSupported("createArrayOf (ARRAY values)");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw notSupported("createStruct (STRUCT values)");
    }

    @Override
    public String toString() {
        return "DbpConnection[" + url.datasource() + "@" + transport.endpoint() + ", session=" + hello.sessionId()
                + (closed ? ", closed" : "") + "]";
    }
}
