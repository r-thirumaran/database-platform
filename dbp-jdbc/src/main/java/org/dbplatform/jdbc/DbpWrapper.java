package org.dbplatform.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Wrapper;

/**
 * Base class of the JDBC objects: {@link Wrapper} for the driver's own types only (vendor unwrapping is not
 * possible because the physical connection lives in the gateway) and the not-supported helper.
 */
abstract class DbpWrapper implements Wrapper {

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("cannot unwrap " + getClass().getSimpleName() + " to "
                + (iface == null ? "null" : iface.getName()) + " (vendor types live in the gateway)", "HY000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    static SQLFeatureNotSupportedException notSupported(String feature) {
        return DbpSqlExceptions.notSupported(feature);
    }
}
