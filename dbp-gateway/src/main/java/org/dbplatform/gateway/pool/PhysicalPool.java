package org.dbplatform.gateway.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.pool.HikariPool;
import org.dbplatform.common.telemetry.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One HikariCP pool for one (physical database, credential version). Holds the cached HELLO_OK server properties
 * and the engine detected from the product name.
 */
public final class PhysicalPool {

    private static final Logger LOG = LoggerFactory.getLogger(PhysicalPool.class);

    /** SQLState reported when no physical connection can be obtained. */
    public static final String STATE_UNABLE = "08001";

    private final PoolSettings settings;
    private final String gatewayId;
    private final HikariDataSource ds;
    private final Set<String> datasourceNames = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pinnedSessions = new AtomicInteger();
    private final long createdAt = System.currentTimeMillis();
    private volatile boolean draining;
    private volatile boolean closed;

    private volatile Map<String, String> serverProperties;
    private volatile String engineName;
    private volatile Engine engine;
    private volatile String defaultSchema;
    private volatile String defaultCatalog;
    private volatile boolean baselineCaptured;
    private final ReentrantLock initLock = new ReentrantLock();
    private final Map<String, String> baselineClientInfo;

    PhysicalPool(PoolSettings settings, String gatewayId) throws SQLException {
        this.settings = settings;
        this.gatewayId = gatewayId;
        this.engine = settings.engineHint();
        this.engineName = ServerProperties.engineName(null, settings.engineHint());
        this.baselineClientInfo = baselineClientInfo(settings.engineHint(), gatewayId);
        datasourceNames.add(settings.datasourceName());
        HikariConfig hc = hikariConfig(settings, gatewayId);
        try {
            this.ds = new HikariDataSource(hc);
        } catch (HikariPool.PoolInitializationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String state = cause instanceof SQLException se && se.getSQLState() != null ? se.getSQLState() : STATE_UNABLE;
            int code = cause instanceof SQLException se ? se.getErrorCode() : 0;
            throw new SQLTransientConnectionException("cannot connect to physical database for datasource '"
                    + settings.datasourceName() + "': " + cause.getMessage(), state, code, cause);
        } catch (RuntimeException e) {
            throw new SQLTransientConnectionException("cannot create pool for datasource '" + settings.datasourceName()
                    + "': " + e.getMessage(), STATE_UNABLE, 0, e);
        }
        LOG.info("pool {} created: {}", key(), settings);
    }

    static HikariConfig hikariConfig(PoolSettings s, String gatewayId) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("dbp-" + s.poolKey().replaceAll("[^A-Za-z0-9_.-]", "_"));
        hc.setJdbcUrl(s.jdbcUrl());
        hc.setUsername(s.username());
        hc.setPassword(s.password());
        hc.setMaximumPoolSize(Math.max(1, s.maxConnections()));
        hc.setMinimumIdle(Math.max(0, Math.min(s.minIdle(), s.maxConnections())));
        hc.setConnectionTimeout(Math.max(250, s.connectionTimeoutMs()));
        hc.setValidationTimeout(Math.max(250, Math.min(5_000, s.connectionTimeoutMs())));
        hc.setIdleTimeout(Math.max(0, s.idleTimeoutMs()));
        hc.setMaxLifetime(Math.max(0, s.maxLifetimeMs()));
        hc.setAutoCommit(true);
        hc.setRegisterMbeans(false);
        hc.setInitializationFailTimeout(1); // fail fast: a broken database surfaces at HELLO as 08001
        if (s.validationQuery() != null && !s.validationQuery().isBlank()) {
            hc.setConnectionTestQuery(s.validationQuery());
        }
        Map<String, String> props = new LinkedHashMap<>(s.jdbcProperties());
        String program = "dbp-gateway/" + gatewayId;
        switch (s.engineHint()) {
            case ORACLE -> {
                props.putIfAbsent("v$session.program", program);
                props.putIfAbsent("oracle.net.CONNECT_TIMEOUT", String.valueOf(Math.max(1000, s.connectionTimeoutMs())));
                if (s.statementTimeoutSeconds() > 0) {
                    props.putIfAbsent("oracle.jdbc.ReadTimeout", String.valueOf((s.statementTimeoutSeconds() + 10) * 1000L));
                }
            }
            case POSTGRES -> {
                props.putIfAbsent("ApplicationName", program);
                props.putIfAbsent("connectTimeout", String.valueOf(Math.max(1, (s.connectionTimeoutMs() + 999) / 1000)));
                // the wire protocol carries UUID, JSON/JSONB, enums, intervals, ... as STRING and the gateway binds them
                // with setString: let the server infer the parameter type instead of forcing varchar (42804 otherwise)
                props.putIfAbsent("stringtype", "unspecified");
                // pgjdbc's default readOnlyMode=transaction only guards explicit transactions: an autocommit UPDATE on a
                // read-only connection would succeed. 'always' makes setReadOnly(true) apply
                // SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY, so the server rejects writes in autocommit too
                // (the gateway's own 25006 check for read-only grants runs before that, for every engine)
                props.putIfAbsent("readOnlyMode", "always");
            }
            case MSSQL -> {
                props.putIfAbsent("applicationName", program);
                props.putIfAbsent("loginTimeout", String.valueOf(Math.max(1, (s.connectionTimeoutMs() + 999) / 1000)));
            }
            default -> {
                // H2 / OTHER: nothing engine specific
            }
        }
        props.forEach(hc::addDataSourceProperty);
        return hc;
    }

    private static Map<String, String> baselineClientInfo(Engine engine, String gatewayId) {
        String program = "dbp-gateway/" + gatewayId;
        return switch (engine) {
            case ORACLE -> Map.of("OCSID.MODULE", program);
            case POSTGRES -> Map.of("ApplicationName", program);
            default -> Map.of();
        };
    }

    // ------------------------------------------------------------------ identity

    public String key() {
        return settings.poolKey();
    }

    public PoolSettings settings() {
        return settings;
    }

    public Engine engine() {
        return engine;
    }

    /** HELLO_OK engine string, exact after {@link #serverProperties()} ran once. */
    public String engineName() {
        return engineName;
    }

    public Set<String> datasourceNames() {
        return Collections.unmodifiableSet(datasourceNames);
    }

    void addDatasourceName(String name) {
        datasourceNames.add(name);
    }

    public long createdAt() {
        return createdAt;
    }

    /** Default schema of the pool user as reported by the driver (nullable, captured on the first borrow). */
    public String defaultSchema() {
        return defaultSchema;
    }

    /** Default catalog of the pool user (nullable, captured on the first borrow). */
    public String defaultCatalog() {
        return defaultCatalog;
    }

    private void captureBaseline(Connection c) {
        initLock.lock();
        try {
            if (baselineCaptured) {
                return;
            }
            try {
                defaultSchema = c.getSchema();
            } catch (SQLException | AbstractMethodError | RuntimeException e) {
                defaultSchema = null;
            }
            try {
                defaultCatalog = c.getCatalog();
            } catch (SQLException | AbstractMethodError | RuntimeException e) {
                defaultCatalog = null;
            }
            baselineCaptured = true;
        } finally {
            initLock.unlock();
        }
    }

    /** Client info entries every physical connection carries when no session info is set. */
    public Map<String, String> baselineClientInfo() {
        return baselineClientInfo;
    }

    // ------------------------------------------------------------------ connections

    /**
     * Borrows a physical connection.
     *
     * @throws SQLException with SQLState {@code 08001} when the pool is exhausted (timeout) or the database is down
     */
    public Connection borrow() throws SQLException {
        if (closed) {
            throw new SQLTransientConnectionException("pool " + key() + " is closed", STATE_UNABLE);
        }
        try {
            Connection c = ds.getConnection();
            pinnedSessions.incrementAndGet();
            if (!baselineCaptured) {
                captureBaseline(c);
            }
            return c;
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? STATE_UNABLE : e.getSQLState();
            throw new SQLTransientConnectionException("cannot obtain a physical connection for datasource '"
                    + settings.datasourceName() + "': " + e.getMessage(), state, e.getErrorCode(), e);
        }
    }

    /** Returns a connection to the pool; {@code evict} discards it instead (broken connection). */
    public void release(Connection c, boolean evict) {
        if (c == null) {
            return;
        }
        pinnedSessions.decrementAndGet();
        try {
            if (evict) {
                ds.evictConnection(c);
            } else {
                c.close();
            }
        } catch (RuntimeException | SQLException e) {
            LOG.debug("release of physical connection failed: {}", e.toString());
        }
    }

    /** Lazily computes and caches the HELLO_OK server properties (borrows a connection the first time). */
    public Map<String, String> serverProperties() throws SQLException {
        Map<String, String> p = serverProperties;
        if (p != null) {
            return p;
        }
        initLock.lock();
        try {
            if (serverProperties != null) {
                return serverProperties;
            }
            Connection c = borrow();
            try {
                DatabaseMetaData md = c.getMetaData();
                Map<String, String> computed = ServerProperties.compute(md);
                String product = computed.get("databaseProductName");
                engine = ServerProperties.detectEngine(product, settings.engineHint());
                engineName = ServerProperties.engineName(product, settings.engineHint());
                serverProperties = Collections.unmodifiableMap(computed);
                return serverProperties;
            } finally {
                release(c, false);
            }
        } finally {
            initLock.unlock();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean isDraining() {
        return draining;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Marks the pool draining: idle connections are evicted now, active ones when they return. */
    public void drain() {
        if (!draining) {
            draining = true;
            LOG.info("pool {} draining (credential rotated)", key());
            try {
                HikariPoolMXBean mx = ds.getHikariPoolMXBean();
                if (mx != null) {
                    mx.softEvictConnections();
                }
            } catch (RuntimeException e) {
                LOG.debug("softEvict failed on {}: {}", key(), e.toString());
            }
        }
    }

    /** Closes the pool when it is draining and nothing is borrowed any more. Returns whether it is closed now. */
    public boolean closeIfDrained() {
        if (closed) {
            return true;
        }
        if (draining && activeConnections() == 0 && pinnedSessions.get() <= 0) {
            close();
            return true;
        }
        return false;
    }

    public void close() {
        if (!closed) {
            closed = true;
            LOG.info("pool {} closed", key());
            ds.close();
        }
    }

    // ------------------------------------------------------------------ stats

    public int activeConnections() {
        return safe(HikariPoolMXBean::getActiveConnections);
    }

    public int idleConnections() {
        return safe(HikariPoolMXBean::getIdleConnections);
    }

    public int totalConnections() {
        return safe(HikariPoolMXBean::getTotalConnections);
    }

    public int waitingThreads() {
        return safe(HikariPoolMXBean::getThreadsAwaitingConnection);
    }

    public int maxConnections() {
        return settings.maxConnections();
    }

    public int pinnedSessions() {
        return pinnedSessions.get();
    }

    private int safe(java.util.function.ToIntFunction<HikariPoolMXBean> f) {
        if (closed) {
            return 0;
        }
        try {
            HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            return mx == null ? 0 : f.applyAsInt(mx);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "PhysicalPool[" + key() + (draining ? ", draining" : "") + (closed ? ", closed" : "") + "]";
    }
}
