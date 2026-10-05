package org.dbplatform.examples.batch;

import java.util.Locale;

/** Physical SQL engine behind the connection, for the few engine specific details. */
public enum Engine {
    ORACLE, POSTGRES;

    /** Explicit override first, then the JDBC URL prefix; {@code jdbc:dbp://} needs the override. */
    public static Engine detect(String jdbcUrl, String override) {
        if (override != null && !override.isBlank()) {
            return switch (override.trim().toUpperCase(Locale.ROOT)) {
                case "ORACLE", "ORA" -> ORACLE;
                case "POSTGRES", "POSTGRESQL", "PG" -> POSTGRES;
                default -> throw new IllegalArgumentException("Unsupported engine '" + override + "'");
            };
        }
        if (jdbcUrl == null) {
            return ORACLE;
        }
        String url = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:")) {
            return POSTGRES;
        }
        return ORACLE;
    }

    public String pingSql() {
        return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }
}
