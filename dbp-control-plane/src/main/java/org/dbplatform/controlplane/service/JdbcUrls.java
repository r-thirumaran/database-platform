package org.dbplatform.controlplane.service;

import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.DatabaseInstance;

/** Builds engine specific JDBC URLs (the exact formats are part of the internal API contract). */
public final class JdbcUrls {
    private JdbcUrls() {}

    public static String of(DatabaseInstance db) {
        return switch (db.getEngine()) {
            case ORACLE -> "jdbc:oracle:thin:@//" + db.getHost() + ":" + db.getPort() + "/" + nz(db.getServiceName());
            case POSTGRES -> "jdbc:postgresql://" + db.getHost() + ":" + db.getPort() + "/" + nz(db.getServiceName());
            case MSSQL -> "jdbc:sqlserver://" + db.getHost() + ":" + db.getPort() + ";databaseName=" + nz(db.getServiceName()) + ";encrypt=false";
            // serviceName is what follows the port: "mem:name", "~/path/db" or "./path/db"
            case H2 -> "jdbc:h2:tcp://" + db.getHost() + ":" + db.getPort() + "/" + nz(db.getServiceName());
            case OTHER -> explicitUrl(db);
        };
    }

    /** The JDBC URL of an {@code OTHER} database: {@code jdbcProperties.url}. */
    private static String explicitUrl(DatabaseInstance db) {
        String url = db.getJdbcProperties() == null ? null : db.getJdbcProperties().get(URL_PROPERTY);
        if (url == null || url.isBlank()) {
            throw new ApiException.BadRequest("Database '" + db.getName() + "' has engine OTHER and therefore needs the full JDBC URL in jdbcProperties.url");
        }
        return url.trim();
    }

    /** Key of {@code jdbcProperties} holding the full JDBC URL of an {@code OTHER} database. */
    public static final String URL_PROPERTY = "url";

    private static String nz(String s) { return s == null ? "" : s; }
}
