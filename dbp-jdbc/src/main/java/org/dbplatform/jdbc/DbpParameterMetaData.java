package org.dbplatform.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * {@link ParameterMetaData} of a prepared statement: the parameter count comes from PREPARED (or, when the
 * gateway reports it as unknown, from the highest parameter index bound so far); parameter types are not
 * carried by the protocol and are reported as {@link Types#OTHER} / {@link #parameterModeUnknown}.
 */
public final class DbpParameterMetaData extends DbpWrapper implements ParameterMetaData {

    private final int count;

    DbpParameterMetaData(int count) {
        this.count = Math.max(0, count);
    }

    private void check(int param) throws SQLException {
        if (param < 1 || param > count) {
            throw DbpSqlExceptions.invalidIndex("parameter index " + param + " out of range 1.." + count);
        }
    }

    @Override
    public int getParameterCount() {
        return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return false;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return Types.OTHER;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return "OTHER";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return "java.lang.Object";
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        check(param);
        return parameterModeUnknown;
    }

    @Override
    public String toString() {
        return "DbpParameterMetaData[" + count + " parameters]";
    }
}
