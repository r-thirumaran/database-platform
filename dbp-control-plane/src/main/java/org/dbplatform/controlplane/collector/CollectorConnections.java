package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
        if (db.getCredentialId() == null) {
            throw new ApiException.Conflict("Database '" + db.getName() + "' has no credential configured");
        }
        CredentialService.Material m = credentials.material(db.getCredentialId(), "collector:" + db.getName());
        Properties p = new Properties();
        for (Map.Entry<String, String> e : db.getJdbcProperties().entrySet()) p.setProperty(e.getKey(), e.getValue());
        if (m.username() != null) p.setProperty("user", m.username());
        if (m.secret() != null) p.setProperty("password", m.secret());
        int timeoutMs = props.getCollector().getConnectTimeoutSeconds() * 1000;
        switch (db.getEngine()) {
            case ORACLE -> {
                p.putIfAbsent("oracle.net.CONNECT_TIMEOUT", String.valueOf(timeoutMs));
                p.putIfAbsent("oracle.jdbc.ReadTimeout", String.valueOf(timeoutMs * 6));
                p.putIfAbsent("v$session.program", "dbp-control-plane");
            }
            case POSTGRES -> {
                p.putIfAbsent("connectTimeout", String.valueOf(props.getCollector().getConnectTimeoutSeconds()));
                p.putIfAbsent("socketTimeout", String.valueOf(props.getCollector().getConnectTimeoutSeconds() * 6));
                p.putIfAbsent("ApplicationName", "dbp-control-plane");
            }
            case MSSQL -> {
                p.putIfAbsent("loginTimeout", String.valueOf(props.getCollector().getConnectTimeoutSeconds()));
                p.putIfAbsent("applicationName", "dbp-control-plane");
            }
        }
        DriverManager.setLoginTimeout(props.getCollector().getConnectTimeoutSeconds());
        return DriverManager.getConnection(JdbcUrls.of(db), p);
    }
}
