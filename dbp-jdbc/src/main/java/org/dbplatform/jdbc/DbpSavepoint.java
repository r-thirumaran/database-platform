package org.dbplatform.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * A savepoint created by {@code Connection.setSavepoint}; the gateway identifies savepoints by name
 * (SAVEPOINT_SET carries the name even for unnamed savepoints).
 */
public final class DbpSavepoint implements Savepoint {

    private final String name;
    private final boolean named;
    private final int id;

    DbpSavepoint(String name, boolean named, int id) {
        this.name = name;
        this.named = named;
        this.id = id;
    }

    /** The name the gateway knows this savepoint by. */
    String wireName() {
        return name;
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (named) {
            throw new SQLException("this is a named savepoint", "HY000");
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (!named) {
            throw new SQLException("this is an unnamed savepoint", "HY000");
        }
        return name;
    }

    @Override
    public String toString() {
        return "DbpSavepoint[" + name + "]";
    }
}
