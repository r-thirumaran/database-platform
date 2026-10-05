package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Database engine (or raw TCP for the pass-through proxy). Unknown values deserialise as {@link #OTHER}. */
public enum Engine {
    ORACLE, POSTGRES, MSSQL, H2, TCP,
    @JsonEnumDefaultValue OTHER;

    /** Lenient parse: case-insensitive, accepts common aliases (postgresql, pg, sqlserver, ...). */
    public static Engine parse(String value) {
        if (value == null) {
            return OTHER;
        }
        String v = value.trim().toUpperCase();
        return switch (v) {
            case "ORACLE", "ORA" -> ORACLE;
            case "POSTGRES", "POSTGRESQL", "PG", "PGSQL" -> POSTGRES;
            case "MSSQL", "SQLSERVER", "SQL_SERVER", "SQL SERVER" -> MSSQL;
            case "H2" -> H2;
            case "TCP" -> TCP;
            default -> OTHER;
        };
    }
}
