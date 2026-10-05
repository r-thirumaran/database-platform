package org.dbplatform.gateway.pool;

import java.util.Locale;

/**
 * How long a logical session keeps a physical connection pinned.
 */
public enum PoolMode {
    /** Pin from the first statement with autocommit off (or an open cursor) until commit/rollback and cursor close. */
    TRANSACTION,
    /** Pin from the first statement for the whole life of the logical session. */
    SESSION;

    /** Lenient parser: {@code null}/blank/unknown yields {@code dflt}. */
    public static PoolMode parse(String value, PoolMode dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return PoolMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
