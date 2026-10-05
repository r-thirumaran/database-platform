package org.dbplatform.controlplane.service;

import org.dbplatform.controlplane.domain.DatabaseInstance;

/** Builds engine specific JDBC URLs (the exact formats are part of the internal API contract). */
public final class JdbcUrls {
    private JdbcUrls() {}

    public static String of(DatabaseInstance db) {
        return switch (db.getEngine()) {
            case ORACLE -> "jdbc:oracle:thin:@//" + db.getHost() + ":" + db.getPort() + "/" + nz(db.getServiceName());
            case POSTGRES -> "jdbc:postgresql://" + db.getHost() + ":" + db.getPort() + "/" + nz(db.getServiceName());
            case MSSQL -> "jdbc:sqlserver://" + db.getHost() + ":" + db.getPort() + ";databaseName=" + nz(db.getServiceName()) + ";encrypt=false";
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
