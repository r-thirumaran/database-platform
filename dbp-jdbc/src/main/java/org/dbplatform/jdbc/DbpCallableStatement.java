package org.dbplatform.jdbc;

import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.messages.Execute.OutParam;
import org.dbplatform.protocol.messages.StatementKind;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link CallableStatement} with positional parameters only. OUT parameters are registered with
 * {@code registerOutParameter(index, …)} and sent as {@code OutParam[]}; INOUT parameters are additionally
 * bound with {@code setXxx}. After execution the values arrive in OUT_PARAMS and are read with the same
 * conversions as {@link DbpResultSet}.
 *
 * <p>A parameter registered as {@link Types#REF_CURSOR} (2012) or the Oracle legacy code {@code -10} is a
 * cursor: the gateway streams it as a result item before OUT_PARAMS and the OUT value is its cursor id;
 * {@code getObject(index)} returns a {@link ResultSet} bound to that cursor and {@code getMoreResults()}
 * skips those items (specification section 4.7).</p>
 *
 * <p>Named parameters ({@code setXxx(String, …)}, {@code getXxx(String)}, {@code registerOutParameter(String, …)})
 * are not supported ({@code 0A000}): the protocol is positional.</p>
 */
public final class DbpCallableStatement extends DbpPreparedStatement implements CallableStatement {

    /** Oracle {@code OracleTypes.CURSOR}. */
    static final int ORACLE_CURSOR = -10;

    private final TreeMap<Integer, OutParam> registered = new TreeMap<>();
    private Map<Integer, Object> outValues = Map.of();
    private final Map<Integer, DbpResultSet> cursorValues = new HashMap<>();
    private boolean lastWasNull;

    DbpCallableStatement(DbpConnection connection, String sql, int resultSetType, int resultSetConcurrency,
                         int resultSetHoldability) throws SQLException {
        super(connection, sql, StatementKind.CALLABLE, resultSetType, resultSetConcurrency, resultSetHoldability,
                NO_GENERATED_KEYS, List.of());
    }

    static boolean isCursorType(int jdbcType) {
        return jdbcType == Types.REF_CURSOR || jdbcType == ORACLE_CURSOR;
    }

    // ---------------------------------------------------------------- hooks

    @Override
    protected List<OutParam> outParams() {
        return new ArrayList<>(registered.values());
    }

    @Override
    protected boolean allowsUnset(int index) {
        return registered.containsKey(index);
    }

    @Override
    protected int parameterSlotCount() {
        int n = super.parameterSlotCount();
        return registered.isEmpty() ? n : Math.max(n, registered.lastKey());
    }

    @Override
    protected List<ExecutionResult.Item> filterItems(ExecutionResult result) throws SQLException {
        outValues = result.outParams == null ? Map.of() : result.outParams.asMap();
        cursorValues.clear();
        List<ExecutionResult.Item> items = new ArrayList<>(result.items);
        for (OutParam p : registered.values()) {
            if (!isCursorType(p.jdbcType())) {
                continue;
            }
            Object v = outValues.get(p.index());
            if (v == null) {
                continue;
            }
            if (!(v instanceof Number n)) {
                throw DbpSqlExceptions.protocolViolation("cursor OUT parameter " + p.index()
                        + " must carry an INT cursor id but got " + v.getClass().getSimpleName());
            }
            int cursorId = n.intValue();
            ExecutionResult.ResultSetItem match = null;
            for (ExecutionResult.Item item : items) {
                if (item instanceof ExecutionResult.ResultSetItem rs && rs.cursorId() == cursorId) {
                    match = rs;
                    break;
                }
            }
            if (match == null) {
                throw DbpSqlExceptions.protocolViolation("cursor OUT parameter " + p.index() + " refers to cursor "
                        + cursorId + " but no such result item was received");
            }
            items.remove(match);
            cursorValues.put(p.index(), newResultSet(match));
        }
        return items;
    }

    // ---------------------------------------------------------------- registration

    private void register(int parameterIndex, int sqlType, int scale, String typeName) throws SQLException {
        checkOpen();
        if (parameterIndex < 1) {
            throw DbpSqlExceptions.invalidIndex("parameter index " + parameterIndex + " must be >= 1");
        }
        registered.put(parameterIndex, new OutParam(parameterIndex, sqlType, scale, typeName));
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        register(parameterIndex, sqlType, ProtocolConstants.NO_SCALE, null);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        register(parameterIndex, sqlType, scale, null);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        register(parameterIndex, sqlType, ProtocolConstants.NO_SCALE, typeName);
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
        registerOutParameter(parameterIndex, vendorType(sqlType));
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex, vendorType(sqlType), scale);
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex, vendorType(sqlType), typeName);
    }

    @Override
    public void clearParameters() throws SQLException {
        super.clearParameters();
    }

    // ---------------------------------------------------------------- OUT values

    private Object out(int parameterIndex) throws SQLException {
        checkOpen();
        if (!registered.containsKey(parameterIndex)) {
            throw DbpSqlExceptions.invalidIndex("parameter " + parameterIndex + " is not registered as an OUT parameter");
        }
        Object v = outValues.get(parameterIndex);
        lastWasNull = v == null;
        return v;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastWasNull;
    }

    @Override
    public String getString(int parameterIndex) throws SQLException {
        return Conversions.toString(out(parameterIndex));
    }

    @Override
    public boolean getBoolean(int parameterIndex) throws SQLException {
        return Conversions.toBoolean(out(parameterIndex));
    }

    @Override
    public byte getByte(int parameterIndex) throws SQLException {
        return Conversions.toByte(out(parameterIndex));
    }

    @Override
    public short getShort(int parameterIndex) throws SQLException {
        return Conversions.toShort(out(parameterIndex));
    }

    @Override
    public int getInt(int parameterIndex) throws SQLException {
        return Conversions.toInt(out(parameterIndex));
    }

    @Override
    public long getLong(int parameterIndex) throws SQLException {
        return Conversions.toLong(out(parameterIndex));
    }

    @Override
    public float getFloat(int parameterIndex) throws SQLException {
        return Conversions.toFloat(out(parameterIndex));
    }

    @Override
    public double getDouble(int parameterIndex) throws SQLException {
        return Conversions.toDouble(out(parameterIndex));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
        return Conversions.toBigDecimal(out(parameterIndex), scale);
    }

    @Override
    public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
        return Conversions.toBigDecimal(out(parameterIndex));
    }

    @Override
    public byte[] getBytes(int parameterIndex) throws SQLException {
        return Conversions.toBytes(out(parameterIndex));
    }

    @Override
    public Date getDate(int parameterIndex) throws SQLException {
        return Conversions.toDate(out(parameterIndex), null);
    }

    @Override
    public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
        return Conversions.toDate(out(parameterIndex), cal);
    }

    @Override
    public Time getTime(int parameterIndex) throws SQLException {
        return Conversions.toTime(out(parameterIndex), null);
    }

    @Override
    public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
        return Conversions.toTime(out(parameterIndex), cal);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex) throws SQLException {
        return Conversions.toTimestamp(out(parameterIndex), null);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
        return Conversions.toTimestamp(out(parameterIndex), cal);
    }

    @Override
    public Object getObject(int parameterIndex) throws SQLException {
        Object v = out(parameterIndex);
        DbpResultSet cursor = cursorValues.get(parameterIndex);
        if (cursor != null) {
            return cursor;
        }
        return Conversions.toJdbcObject(v);
    }

    @Override
    public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw notSupported("custom type maps");
        }
        return getObject(parameterIndex);
    }

    @Override
    public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        Object v = out(parameterIndex);
        DbpResultSet cursor = cursorValues.get(parameterIndex);
        if (cursor != null && type != null && type.isAssignableFrom(DbpResultSet.class)) {
            return type.cast(cursor);
        }
        return Conversions.convert(v, type);
    }

    @Override
    public Ref getRef(int parameterIndex) throws SQLException {
        throw notSupported("getRef");
    }

    @Override
    public Blob getBlob(int parameterIndex) throws SQLException {
        byte[] b = getBytes(parameterIndex);
        return b == null ? null : new DbpBlob(b);
    }

    @Override
    public Clob getClob(int parameterIndex) throws SQLException {
        String s = getString(parameterIndex);
        return s == null ? null : new DbpClob(s);
    }

    @Override
    public NClob getNClob(int parameterIndex) throws SQLException {
        String s = getString(parameterIndex);
        return s == null ? null : new DbpClob(s);
    }

    @Override
    public Array getArray(int parameterIndex) throws SQLException {
        throw notSupported("getArray");
    }

    @Override
    public URL getURL(int parameterIndex) throws SQLException {
        throw notSupported("getURL");
    }

    @Override
    public RowId getRowId(int parameterIndex) throws SQLException {
        throw notSupported("getRowId");
    }

    @Override
    public SQLXML getSQLXML(int parameterIndex) throws SQLException {
        throw notSupported("getSQLXML");
    }

    @Override
    public String getNString(int parameterIndex) throws SQLException {
        return getString(parameterIndex);
    }

    @Override
    public Reader getNCharacterStream(int parameterIndex) throws SQLException {
        return getCharacterStream(parameterIndex);
    }

    @Override
    public Reader getCharacterStream(int parameterIndex) throws SQLException {
        String s = getString(parameterIndex);
        return s == null ? null : new java.io.StringReader(s);
    }

    // ---------------------------------------------------------------- named parameters (unsupported)

    private static SQLException named() {
        return notSupported("named parameters on CallableStatement (the DBP protocol is positional; "
                + "use parameter indexes)");
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType, int scale) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType, String typeName) throws SQLException {
        throw named();
    }

    @Override
    public void setURL(String parameterName, URL val) throws SQLException {
        throw named();
    }

    @Override
    public void setNull(String parameterName, int sqlType) throws SQLException {
        throw named();
    }

    @Override
    public void setBoolean(String parameterName, boolean x) throws SQLException {
        throw named();
    }

    @Override
    public void setByte(String parameterName, byte x) throws SQLException {
        throw named();
    }

    @Override
    public void setShort(String parameterName, short x) throws SQLException {
        throw named();
    }

    @Override
    public void setInt(String parameterName, int x) throws SQLException {
        throw named();
    }

    @Override
    public void setLong(String parameterName, long x) throws SQLException {
        throw named();
    }

    @Override
    public void setFloat(String parameterName, float x) throws SQLException {
        throw named();
    }

    @Override
    public void setDouble(String parameterName, double x) throws SQLException {
        throw named();
    }

    @Override
    public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        throw named();
    }

    @Override
    public void setString(String parameterName, String x) throws SQLException {
        throw named();
    }

    @Override
    public void setBytes(String parameterName, byte[] x) throws SQLException {
        throw named();
    }

    @Override
    public void setDate(String parameterName, Date x) throws SQLException {
        throw named();
    }

    @Override
    public void setTime(String parameterName, Time x) throws SQLException {
        throw named();
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
        throw named();
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
        throw named();
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType, int scale) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(String parameterName, Object x) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(String parameterName, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(String parameterName, Object x, SQLType targetSqlType) throws SQLException {
        throw named();
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {
        throw named();
    }

    @Override
    public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
        throw named();
    }

    @Override
    public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
        throw named();
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
        throw named();
    }

    @Override
    public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        throw named();
    }

    @Override
    public void setRowId(String parameterName, RowId x) throws SQLException {
        throw named();
    }

    @Override
    public void setNString(String parameterName, String value) throws SQLException {
        throw named();
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setNClob(String parameterName, NClob value) throws SQLException {
        throw named();
    }

    @Override
    public void setClob(String parameterName, Reader reader, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
        throw named();
    }

    @Override
    public void setBlob(String parameterName, Blob x) throws SQLException {
        throw named();
    }

    @Override
    public void setClob(String parameterName, Clob x) throws SQLException {
        throw named();
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        throw named();
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
        throw named();
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
        throw named();
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
        throw named();
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
        throw named();
    }

    @Override
    public void setClob(String parameterName, Reader reader) throws SQLException {
        throw named();
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
        throw named();
    }

    @Override
    public void setNClob(String parameterName, Reader reader) throws SQLException {
        throw named();
    }

    @Override
    public String getString(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public boolean getBoolean(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public byte getByte(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public short getShort(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public int getInt(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public long getLong(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public float getFloat(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public double getDouble(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public byte[] getBytes(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Date getDate(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Time getTime(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Timestamp getTimestamp(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Object getObject(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public BigDecimal getBigDecimal(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
        throw named();
    }

    @Override
    public Ref getRef(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Blob getBlob(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Clob getClob(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Array getArray(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Date getDate(String parameterName, Calendar cal) throws SQLException {
        throw named();
    }

    @Override
    public Time getTime(String parameterName, Calendar cal) throws SQLException {
        throw named();
    }

    @Override
    public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
        throw named();
    }

    @Override
    public URL getURL(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public RowId getRowId(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public NClob getNClob(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public SQLXML getSQLXML(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public String getNString(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Reader getNCharacterStream(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public Reader getCharacterStream(String parameterName) throws SQLException {
        throw named();
    }

    @Override
    public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
        throw named();
    }
}
