package org.dbplatform.proxy.config;

/** Protocol handled by a listener. {@code TCP} is an opaque pass-through, as is {@code MSSQL} in the POC. */
public enum Engine {
    ORACLE, POSTGRES, MSSQL, TCP;

    /** Conventional default listener port (see CONTRIBUTING.md). */
    public int defaultPort() {
        return switch (this) {
            case ORACLE -> 1521;
            case POSTGRES -> 5432;
            case MSSQL -> 1433;
            case TCP -> 0;
        };
    }
}
