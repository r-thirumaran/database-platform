package org.dbplatform.jdbc;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.messages.CloseCursor;
import org.dbplatform.protocol.messages.Fetch;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Rows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forward-only, read-only {@link ResultSet} over a server-side cursor: the first ROWS batch arrives with the
 * RESULT_SET_HEADER, further batches are pulled with {@code FETCH(cursorId, fetchSize)} until the gateway
 * flags the last batch (the server then closes the cursor; otherwise {@code close()} sends CLOSE_CURSOR).
 *
 * <p>Also used for in-memory result sets (generated keys) with {@code cursorId = -1}.</p>
 *
 * <p>Scrolling ({@code absolute}, {@code previous}, …) and updates ({@code updateXxx}, {@code insertRow}, …)
 * raise {@link java.sql.SQLFeatureNotSupportedException} (SQLState {@code 0A000}).</p>
 */
public final class DbpResultSet extends DbpWrapper implements ResultSet {

    private final DbpConnection connection;
    private final DbpStatement statement;
    private final int cursorId;
    private final List<ColumnMeta> columns;
    private final long maxRows;
    private final DbpWarningChain warnings = new DbpWarningChain();

    private List<List<Object>> batch;
    private int batchPos = -1;
    private boolean lastBatch;
    private boolean afterLast;
    private long rowNumber;
    private int fetchSize;
    private boolean closed;
    private boolean wasNull;
    private Map<String, Integer> labelIndex;
    private DbpResultSetMetaData metaData;

    DbpResultSet(DbpConnection connection, DbpStatement statement, int cursorId, List<ColumnMeta> columns,
                 List<List<Object>> firstRows, boolean last, int fetchSize, long maxRows) {
        this.connection = connection;
        this.statement = statement;
        this.cursorId = cursorId;
        this.columns = columns == null ? List.of() : columns;
        this.batch = firstRows == null ? List.of() : firstRows;
        this.lastBatch = last;
        this.fetchSize = fetchSize;
        this.maxRows = maxRows;
    }

    /** Result set over rows already in memory (generated keys, empty results). */
    static DbpResultSet inMemory(DbpConnection connection, DbpStatement statement, List<ColumnMeta> columns,
                                 List<List<Object>> rows) {
        return new DbpResultSet(connection, statement, -1, columns, rows, true, 0, 0);
    }

    int cursorId() {
        return cursorId;
    }

    List<ColumnMeta> columns() {
        return columns;
    }

    // ---------------------------------------------------------------- cursor movement

    private void checkOpen() throws SQLException {
        if (closed) {
            throw DbpSqlExceptions.resultSetClosed();
        }
    }

    private int effectiveFetchSize() {
        return fetchSize > 0 ? fetchSize : ProtocolConstants.DEFAULT_FETCH_SIZE;
    }

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (afterLast) {
            return false;
        }
        if (maxRows > 0 && rowNumber >= maxRows) {
            afterLast = true;
            return false;
        }
        while (batchPos + 1 >= batch.size()) {
            if (lastBatch) {
                afterLast = true;
                batchPos = batch.size();
                return false;
            }
            fetchNextBatch();
        }
        batchPos++;
        rowNumber++;
        return true;
    }

    private void fetchNextBatch() throws SQLException {
        List<Message> replies = connection.exchange(new Fetch(cursorId, effectiveFetchSize()), columns.size());
        Message m = replies.get(replies.size() - 1);
        if (!(m instanceof Rows rows)) {
            throw DbpSqlExceptions.protocolViolation("expected ROWS in response to FETCH but got " + m.type());
        }
        if (rows.cursorId() != cursorId) {
            throw DbpSqlExceptions.protocolViolation("FETCH returned rows of cursor " + rows.cursorId()
                    + " instead of " + cursorId);
        }
        batch = rows.rows();
        batchPos = -1;
        lastBatch = rows.last();
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (cursorId >= 0 && !lastBatch && !connection.isClosed()) {
                connection.exchangeOk(new CloseCursor(cursorId));
            }
        } finally {
            lastBatch = true;
            batch = List.of();
            if (statement != null) {
                statement.resultSetClosed(this);
            }
        }
    }

    /** Marks the result set closed without talking to the gateway (connection/statement teardown). */
    void closeLocally() {
        closed = true;
        lastBatch = true;
        batch = List.of();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return rowNumber == 0 && !afterLast && !(batch.isEmpty() && lastBatch);
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return afterLast && rowNumber > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return rowNumber == 1 && !afterLast;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        if (afterLast || rowNumber == 0) {
            return false;
        }
        if (maxRows > 0 && rowNumber >= maxRows) {
            return true;
        }
        if (batchPos == batch.size() - 1) {
            if (lastBatch) {
                return true;
            }
            throw notSupported("isLast() before the cursor is exhausted (forward-only streaming result set)");
        }
        return false;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return afterLast ? 0 : (int) Math.min(Integer.MAX_VALUE, rowNumber);
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public void afterLast() throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public boolean first() throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public boolean last() throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public boolean previous() throws SQLException {
        throw notSupported("scrollable result sets");
    }

    @Override
    public void refreshRow() throws SQLException {
        throw notSupported("refreshRow");
    }

    // ---------------------------------------------------------------- properties

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings.get();
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings.clear();
    }

    DbpWarningChain warningChain() {
        return warnings;
    }

    @Override
    public String getCursorName() throws SQLException {
        throw notSupported("named cursors");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        if (metaData == null) {
            metaData = new DbpResultSetMetaData(columns);
        }
        return metaData;
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        if (columnLabel == null) {
            throw DbpSqlExceptions.invalidIndex("column label must not be null");
        }
        if (labelIndex == null) {
            Map<String, Integer> idx = new HashMap<>();
            // names first, then labels so that labels win on conflicts
            for (int i = columns.size() - 1; i >= 0; i--) {
                String n = columns.get(i).name();
                if (n != null) {
                    idx.put(n.toLowerCase(), i + 1);
                }
            }
            for (int i = columns.size() - 1; i >= 0; i--) {
                String l = columns.get(i).label();
                if (l != null) {
                    idx.put(l.toLowerCase(), i + 1);
                }
            }
            labelIndex = idx;
        }
        Integer i = labelIndex.get(columnLabel.toLowerCase());
        if (i == null) {
            throw new SQLException("column '" + columnLabel + "' not found", "42S22");
        }
        return i;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw DbpSqlExceptions.invalidArgument("only FETCH_FORWARD is supported");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) {
            throw DbpSqlExceptions.invalidArgument("fetch size must be >= 0");
        }
        this.fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    // ---------------------------------------------------------------- value access

    private Object raw(int columnIndex) throws SQLException {
        checkOpen();
        if (afterLast || batchPos < 0 || batchPos >= batch.size()) {
            throw DbpSqlExceptions.invalidCursorState(rowNumber == 0 ? "result set is positioned before the first row"
                    : "result set is positioned after the last row");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw DbpSqlExceptions.invalidIndex("column index " + columnIndex + " out of range 1.." + columns.size());
        }
        Object v = batch.get(batchPos).get(columnIndex - 1);
        wasNull = v == null;
        return v;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return Conversions.toString(raw(columnIndex));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return Conversions.toBoolean(raw(columnIndex));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return Conversions.toByte(raw(columnIndex));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return Conversions.toShort(raw(columnIndex));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return Conversions.toInt(raw(columnIndex));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return Conversions.toLong(raw(columnIndex));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return Conversions.toFloat(raw(columnIndex));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return Conversions.toDouble(raw(columnIndex));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return Conversions.toBigDecimal(raw(columnIndex), scale);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return Conversions.toBigDecimal(raw(columnIndex));
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return Conversions.toBytes(raw(columnIndex));
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return Conversions.toDate(raw(columnIndex), null);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return Conversions.toDate(raw(columnIndex), cal);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return Conversions.toTime(raw(columnIndex), null);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return Conversions.toTime(raw(columnIndex), cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return Conversions.toTimestamp(raw(columnIndex), null);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return Conversions.toTimestamp(raw(columnIndex), cal);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_16));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new StringReader(s);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return Conversions.toJdbcObject(raw(columnIndex));
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw notSupported("custom type maps");
        }
        return getObject(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return Conversions.convert(raw(columnIndex), type);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new DbpBlob(b);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new DbpClob(s);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new DbpClob(s);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw notSupported("getRef");
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw notSupported("getArray (ARRAY columns arrive as STRING)");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw notSupported("getURL");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw notSupported("getRowId");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw notSupported("getSQLXML (XML columns arrive as STRING)");
    }

    // ---------------------------------------------------------------- value access by label

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getNCharacterStream(findColumn(columnLabel));
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getNString(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return getRef(findColumn(columnLabel));
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return getRowId(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return getSQLXML(findColumn(columnLabel));
    }

    // ---------------------------------------------------------------- updates (unsupported)

    private static SQLException notUpdatable() {
        return notSupported("updatable result sets");
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void insertRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public String toString() {
        return "DbpResultSet[cursor=" + cursorId + ", columns=" + columns.size() + ", row=" + rowNumber
                + (closed ? ", closed" : "") + "]";
    }
}
