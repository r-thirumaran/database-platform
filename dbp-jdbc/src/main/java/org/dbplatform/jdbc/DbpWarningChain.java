package org.dbplatform.jdbc;

import org.dbplatform.protocol.Warning;

import java.sql.SQLWarning;
import java.util.List;

/**
 * Helper holding a chain of {@link SQLWarning}s for a connection, statement or result set.
 * Thread-safe (JDBC objects may be touched from several threads).
 */
final class DbpWarningChain {

    private SQLWarning first;
    private SQLWarning last;

    synchronized void add(SQLWarning warning) {
        if (warning == null) {
            return;
        }
        if (first == null) {
            first = warning;
        } else {
            last.setNextWarning(warning);
        }
        last = warning;
        while (last.getNextWarning() != null) {
            last = last.getNextWarning();
        }
    }

    void add(String message, String sqlState) {
        add(new SQLWarning(message, sqlState));
    }

    void addAll(List<Warning> warnings) {
        if (warnings != null) {
            for (Warning w : warnings) {
                add(w.toSqlWarning());
            }
        }
    }

    synchronized SQLWarning get() {
        return first;
    }

    synchronized void clear() {
        first = null;
        last = null;
    }
}
