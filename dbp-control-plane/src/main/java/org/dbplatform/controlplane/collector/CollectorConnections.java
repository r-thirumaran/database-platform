package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.service.CredentialService;
import org.dbplatform.controlplane.service.JdbcUrls;
import org.springframework.stereotype.Component;

/** Opens plain JDBC connections to a physical database with the database's platform credential. */
@Component
public class CollectorConnections {
    private final CredentialService credentials;
    private final DbpProperties props;

    public CollectorConnections(CredentialService credentials, DbpProperties props) {
        this.credentials = credentials;
        this.props = props;
    }

    public Connection open(DatabaseInstance db) throws SQLException {
        String credentialId = db.getCollector().getCredentialId() != null ? db.getCollector().getCredentialId() : db.getCredentialId();
        if (credentialId == null) {
            throw new ApiException.Conflict("Database '" + db.getName() + "' has no credential configured");
        }
        CredentialService.Material m = credentials.material(credentialId, "collector:" + db.getName());
        Properties p = properties(db, m.username(), m.secret(), props.getCollector().getConnectTimeoutSeconds());
        DriverManager.setLoginTimeout(props.getCollector().getConnectTimeoutSeconds());
        return DriverManager.getConnection(JdbcUrls.of(db), p);
    }

    /**
     * {@code jdbcProperties} keys that describe the <em>connection</em> (TLS, timeouts) and therefore also apply to the
     * collector's own session. Everything else is gateway oriented session behaviour ({@code currentSchema},
     * {@code escapeSyntaxCallMode}, {@code stringtype}, {@code readOnlyMode}, {@code ApplicationName}, ...): a collector
     * connection that inherited {@code currentSchema=sales} could no longer see {@code public.pg_stat_statements}.
     */
    static final List<String> CONNECTION_LEVEL_PREFIXES = List.of("ssl", "connectTimeout", "loginTimeout", "socketTimeout",
            "oracle.net.", "oracle.jdbc.ReadTimeout", "encrypt", "trustServerCertificate");

    /** Name the collector's sessions carry on the database (pg_stat_activity.application_name, V$SESSION.PROGRAM, ...). */
    static final String APPLICATION_NAME = "dbp-collector";

    static boolean isConnectionLevel(String key) {
        if (key == null) return false;
        for (String prefix : CONNECTION_LEVEL_PREFIXES) if (key.startsWith(prefix)) return true;
        return false;
    }

    /** Driver properties of a collector connection: connection-level {@code jdbcProperties} only, credentials, timeouts, application name. */
    static Properties properties(DatabaseInstance db, String username, String secret, int connectTimeoutSeconds) {
        Properties p = new Properties();
        if (db.getJdbcProperties() != null) {
            for (Map.Entry<String, String> e : db.getJdbcProperties().entrySet()) {
                if (e.getValue() != null && isConnectionLevel(e.getKey())) p.setProperty(e.getKey(), e.getValue());
            }
        }
        if (username != null) p.setProperty("user", username);
        if (secret != null) p.setProperty("password", secret);
        int timeoutMs = connectTimeoutSeconds * 1000;
        switch (db.getEngine()) {
            case ORACLE -> {
                p.putIfAbsent("oracle.net.CONNECT_TIMEOUT", String.valueOf(timeoutMs));
                p.putIfAbsent("oracle.jdbc.ReadTimeout", String.valueOf(timeoutMs * 6));
                p.setProperty("v$session.program", APPLICATION_NAME);
            }
            case POSTGRES -> {
                p.putIfAbsent("connectTimeout", String.valueOf(connectTimeoutSeconds));
                p.putIfAbsent("socketTimeout", String.valueOf(connectTimeoutSeconds * 6));
                p.setProperty("ApplicationName", APPLICATION_NAME);
            }
            case MSSQL -> {
                p.putIfAbsent("loginTimeout", String.valueOf(connectTimeoutSeconds));
                p.setProperty("applicationName", APPLICATION_NAME);
            }
            case H2, OTHER -> { } // no collector for these engines; connections only serve test-connection
        }
        return p;
    }
}
