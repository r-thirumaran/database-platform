package org.dbplatform.jdbc;

import org.dbplatform.protocol.JdbcUrl;
import org.dbplatform.protocol.ProtocolConstants;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC 4.3 driver for {@code jdbc:dbp://gateway-host[:port][,host2[:port2]]/<datasource>[?prop=value...]}.
 *
 * <p>The driver registers itself with {@link DriverManager} when the class is loaded and is also discoverable
 * through {@code META-INF/services/java.sql.Driver}. Connection properties may be given in the URL or in the
 * {@link Properties} argument (URL values win); {@code user}/{@code password} from
 * {@code DriverManager.getConnection(url, user, password)} map to the informational {@code user} and, when
 * {@code apiKey} is absent, {@code password} is used as the api key.</p>
 */
public final class DbpDriver implements Driver {

    /** Application credential issued by the control plane. */
    public static final String PROP_API_KEY = "apiKey";
    /** Application name hint. */
    public static final String PROP_APPLICATION = "application";
    /** Informational user name. */
    public static final String PROP_USER = "user";
    /** Fallback for {@link #PROP_API_KEY} (Hikari/Spring {@code password}). */
    public static final String PROP_PASSWORD = "password";
    /** {@code true} to connect with TLS. */
    public static final String PROP_SSL = "ssl";
    /** Connect timeout per host in milliseconds. */
    public static final String PROP_CONNECT_TIMEOUT_MS = "connectTimeoutMs";
    /** Socket read timeout in milliseconds ({@code 0} = none). */
    public static final String PROP_SOCKET_TIMEOUT_MS = "socketTimeoutMs";
    /** Default fetch size of statements. */
    public static final String PROP_FETCH_SIZE = "fetchSize";
    /** Maximum frame size in bytes. */
    public static final String PROP_MAX_FRAME_BYTES = ProtocolConstants.PROP_MAX_FRAME_BYTES;
    /** Initial auto-commit mode. */
    public static final String PROP_AUTO_COMMIT = "autoCommit";
    /** Initial read-only flag. */
    public static final String PROP_READ_ONLY = "readOnly";
    /** Initial schema. */
    public static final String PROP_SCHEMA = "schema";
    /** Initial transaction isolation (constant value or name). */
    public static final String PROP_TX_ISOLATION = "txIsolation";
    /** Prefix of initial client info entries. */
    public static final String PROP_CLIENT_INFO_PREFIX = "clientInfo.";

    /** Default connect timeout in milliseconds. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    private static final Logger PARENT_LOGGER = Logger.getLogger("org.dbplatform.jdbc");
    private static final DbpDriver INSTANCE = new DbpDriver();
    private static boolean registered;

    static {
        register();
    }

    /** Public no-arg constructor for {@code Class.forName}/{@code ServiceLoader}; use {@link #instance()} otherwise. */
    public DbpDriver() {
    }

    /**
     * Returns the shared driver instance.
     *
     * @return the instance registered with {@link DriverManager}
     */
    public static DbpDriver instance() {
        return INSTANCE;
    }

    /** Registers the driver with {@link DriverManager} (idempotent). */
    public static synchronized void register() {
        if (!registered) {
            try {
                DriverManager.registerDriver(INSTANCE);
                registered = true;
            } catch (SQLException e) {
                throw new IllegalStateException("cannot register the DBP JDBC driver", e);
            }
        }
    }

    /**
     * Deregisters the driver from {@link DriverManager}.
     *
     * @throws SQLException from {@link DriverManager#deregisterDriver}
     */
    public static synchronized void deregister() throws SQLException {
        if (registered) {
            DriverManager.deregisterDriver(INSTANCE);
            registered = false;
        }
    }

    /**
     * Returns whether the driver is currently registered with {@link DriverManager}.
     *
     * @return {@code true} if registered
     */
    public static synchronized boolean isRegistered() {
        return registered;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        JdbcUrl parsed;
        try {
            parsed = JdbcUrl.parse(url);
        } catch (IllegalArgumentException e) {
            throw new SQLNonTransientConnectionException("invalid DBP JDBC URL: " + e.getMessage(), "08001", e);
        }
        return DbpConnection.open(parsed, info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return JdbcUrl.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Map<String, String> merged;
        try {
            merged = JdbcUrl.parse(url).mergedProperties(info);
        } catch (RuntimeException e) {
            merged = new java.util.LinkedHashMap<>();
            if (info != null) {
                for (String n : info.stringPropertyNames()) {
                    merged.put(n, info.getProperty(n));
                }
            }
        }
        List<DriverPropertyInfo> out = new ArrayList<>();
        out.add(prop(PROP_API_KEY, merged, null, "Application credential issued by the control plane (dbp_<id>_<secret>)", null));
        out.add(prop(PROP_APPLICATION, merged, null, "Application name hint", null));
        out.add(prop(PROP_USER, merged, null, "Informational user name", null));
        out.add(prop(PROP_PASSWORD, merged, null, "Used as apiKey when apiKey is absent", null));
        out.add(prop(PROP_SSL, merged, "false", "Connect with TLS", new String[] {"true", "false"}));
        out.add(prop(PROP_CONNECT_TIMEOUT_MS, merged, Integer.toString(DEFAULT_CONNECT_TIMEOUT_MS), "Connect timeout per host (ms)", null));
        out.add(prop(PROP_SOCKET_TIMEOUT_MS, merged, "0", "Socket read timeout (ms), 0 = none", null));
        out.add(prop(PROP_FETCH_SIZE, merged, Integer.toString(ProtocolConstants.DEFAULT_FETCH_SIZE), "Default fetch size", null));
        out.add(prop(PROP_MAX_FRAME_BYTES, merged, Integer.toString(ProtocolConstants.DEFAULT_MAX_FRAME_BYTES), "Maximum frame size (bytes)", null));
        out.add(prop(PROP_AUTO_COMMIT, merged, "true", "Initial auto-commit mode", new String[] {"true", "false"}));
        out.add(prop(PROP_READ_ONLY, merged, null, "Initial read-only flag", new String[] {"true", "false"}));
        out.add(prop(PROP_SCHEMA, merged, null, "Initial schema", null));
        out.add(prop(PROP_TX_ISOLATION, merged, null, "Initial transaction isolation (java.sql.Connection constant or name)",
                new String[] {"1", "2", "4", "8"}));
        return out.toArray(new DriverPropertyInfo[0]);
    }

    private static DriverPropertyInfo prop(String name, Map<String, String> values, String dflt, String description,
                                           String[] choices) {
        DriverPropertyInfo p = new DriverPropertyInfo(name, values.getOrDefault(name, dflt));
        p.description = description;
        p.required = false;
        p.choices = choices;
        return p;
    }

    @Override
    public int getMajorVersion() {
        return DriverVersion.MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return DriverVersion.MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return PARENT_LOGGER;
    }

    @Override
    public String toString() {
        return DriverVersion.DRIVER_NAME + " " + DriverVersion.VERSION;
    }
}
