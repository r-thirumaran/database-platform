package org.dbplatform.gateway.session;

import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.gateway.telemetry.GatewayTelemetry;
import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolOutput;
import org.dbplatform.protocol.TypedNull;
import org.dbplatform.protocol.ValueTag;
import org.dbplatform.protocol.Values;
import org.dbplatform.protocol.Warning;
import org.dbplatform.protocol.messages.BatchResult;
import org.dbplatform.protocol.messages.ErrorMessage;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteBatch;
import org.dbplatform.protocol.messages.ExecuteDone;
import org.dbplatform.protocol.messages.Fetch;
import org.dbplatform.protocol.messages.Messages;
import org.dbplatform.protocol.messages.Metadata;
import org.dbplatform.protocol.messages.OutParams;
import org.dbplatform.protocol.messages.ResultSetHeader;
import org.dbplatform.protocol.messages.RowsWriter;
import org.dbplatform.protocol.messages.StatementKind;
import org.dbplatform.protocol.messages.UpdateCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Executes EXECUTE / FETCH / EXECUTE_BATCH / METADATA requests of one logical session against its physical
 * connection, streaming the response frames (wire protocol sections 4.2, 4.4, 4.6 and 4.7).
 */
public final class StatementExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StatementExecutor.class);

    /** Oracle legacy {@code OracleTypes.CURSOR}. */
    public static final int ORACLE_CURSOR = -10;
    /** {@code OracleTypes.TIMESTAMPTZ}. */
    static final int ORACLE_TIMESTAMPTZ = -101;
    /** {@code OracleTypes.TIMESTAMPLTZ}. */
    static final int ORACLE_TIMESTAMPLTZ = -102;
    /** {@code microsoft.sql.Types.DATETIMEOFFSET}. */
    static final int MSSQL_DATETIMEOFFSET = -155;

    static final Set<String> METADATA_OPERATIONS = Set.of(
            "getTables", "getColumns", "getSchemas", "getCatalogs", "getTableTypes", "getPrimaryKeys",
            "getImportedKeys", "getExportedKeys", "getCrossReference", "getIndexInfo", "getTypeInfo", "getProcedures",
            "getProcedureColumns", "getFunctions", "getFunctionColumns", "getBestRowIdentifier", "getVersionColumns",
            "getTablePrivileges", "getColumnPrivileges", "getUDTs", "getSuperTables", "getSuperTypes", "getAttributes",
            "getClientInfoProperties", "getPseudoColumns");

    private static final int MAX_WARNINGS = 100;

    /** Operations a read-only grant rejects before any physical call. */
    static final Set<SqlOperation> WRITE_OPERATIONS = EnumSet.of(SqlOperation.INSERT, SqlOperation.UPDATE,
            SqlOperation.DELETE, SqlOperation.MERGE, SqlOperation.DDL);
    /** SQLState {@code read_only_sql_transaction}. */
    static final String STATE_READ_ONLY = "25006";

    private final LogicalSession session;
    private final FrameWriter out;
    private final int rowsFrameSoftBytes;
    private final GatewayTelemetry telemetry;

    public StatementExecutor(LogicalSession session, FrameWriter out, int rowsFrameSoftBytes, GatewayTelemetry telemetry) {
        this.session = session;
        this.out = out;
        this.rowsFrameSoftBytes = rowsFrameSoftBytes;
        this.telemetry = telemetry;
    }

    // ------------------------------------------------------------------ EXECUTE

    public void execute(Execute req) throws SQLException, IOException {
        long start = System.nanoTime();
        boolean pinnedBefore = session.isPinned();
        PreparedEntry entry = req.isDirect() ? null : session.prepared(req.statementId());
        String sql = req.isDirect() ? req.sql() : entry.sql();
        StatementKind kind = entry != null ? entry.kind() : req.kind();
        Execute.ExecOptions opt = req.options();
        long rows = -1;
        SQLException failure = null;
        Set<Integer> cursorsBefore = session.cursorIds();
        try {
            enforceReadOnly(sql);
            Connection c = session.acquire();
            session.noteStatementAttempt();
            int genKeys = opt.autoGeneratedKeys();
            List<String> genCols = opt.generatedKeyColumns();
            boolean wantsKeys = !genCols.isEmpty() || genKeys == Statement.RETURN_GENERATED_KEYS;
            if (entry != null && !wantsKeys && entry.wantsGeneratedKeys()) {
                genKeys = entry.autoGeneratedKeys();
                genCols = entry.keyColumns();
                wantsKeys = true;
            }
            Statement st;
            boolean owns;
            if (kind == StatementKind.STATEMENT) {
                st = c.createStatement();
                owns = true;
            } else if (entry != null) {
                st = session.physicalStatement(entry, genKeys, genCols);
                owns = false;
            } else {
                st = LogicalSession.createStatement(c, sql, kind, genKeys, genCols);
                owns = true;
            }
            boolean retained = false;
            List<Warning> warnings;
            try {
                applyOptions(st, opt);
                if (st instanceof PreparedStatement ps) {
                    Set<Integer> outIndexes = Set.of();
                    if (ps instanceof CallableStatement cs && !req.outParams().isEmpty()) {
                        outIndexes = new HashSet<>();
                        for (Execute.OutParam op : req.outParams()) {
                            registerOut(cs, op);
                            outIndexes.add(op.index());
                        }
                    }
                    bindParams(ps, req.params(), outIndexes);
                }
                boolean hasResultSet;
                if (st instanceof PreparedStatement ps) {
                    hasResultSet = ps.execute();
                } else if (!genCols.isEmpty()) {
                    hasResultSet = st.execute(sql, genCols.toArray(new String[0]));
                } else if (genKeys == Statement.RETURN_GENERATED_KEYS) {
                    hasResultSet = st.execute(sql, Statement.RETURN_GENERATED_KEYS);
                } else {
                    hasResultSet = st.execute(sql);
                }
                session.noteWork();
                // read generated keys now: some drivers (H2) discard them on getMoreResults()
                byte[] generatedKeys = wantsKeys ? generatedKeysPayload(st) : null;
                int fetch = fetchSize(opt.fetchSize());
                int statementId = entry != null ? entry.id() : ProtocolConstants.DIRECT_STATEMENT_ID;

                switch (opt.expect()) {
                    case QUERY -> {
                        if (!hasResultSet) {
                            throw new SQLException("statement did not produce a result set (executeQuery on a non-query)",
                                    ErrorMessage.STATE_WRONG_RESULT_SHAPE);
                        }
                        ResultSet rs = st.getResultSet();
                        Streamed s = streamResultSet(st, rs, owns, false, statementId, fetch);
                        rows = s.rows;
                        retained |= s.retained;
                    }
                    case UPDATE -> {
                        if (hasResultSet) {
                            closeQuietly(st.getResultSet());
                            throw new SQLException("statement produced a result set but an update count was expected",
                                    ErrorMessage.STATE_WRONG_RESULT_SHAPE);
                        }
                        long count = updateCount(st);
                        rows = count;
                        Messages.write(out, new UpdateCount(count));
                    }
                    case ANY -> {
                        boolean isRs = hasResultSet;
                        boolean first = true;
                        while (true) {
                            if (isRs) {
                                Streamed s = streamResultSet(st, st.getResultSet(), owns, false, statementId, fetch);
                                if (first) {
                                    rows = s.rows;
                                }
                                first = false;
                                retained |= s.retained;
                                if (s.retained) {
                                    // the client holds an open cursor on this result: only advance when the driver
                                    // can keep it open, otherwise assume a single result
                                    Boolean next = moreResultsKeepingCurrent(st);
                                    if (next == null) {
                                        break;
                                    }
                                    isRs = next;
                                } else {
                                    isRs = st.getMoreResults();
                                }
                            } else {
                                long count = updateCount(st);
                                if (count == -1) {
                                    break;
                                }
                                if (first) {
                                    rows = count;
                                }
                                first = false;
                                Messages.write(out, new UpdateCount(count));
                                isRs = st.getMoreResults();
                            }
                        }
                    }
                }
                if (st instanceof CallableStatement cs && !req.outParams().isEmpty()) {
                    retained |= writeOutParams(cs, req.outParams(), owns, statementId, fetch);
                }
                if (generatedKeys != null) {
                    out.writeFrame(MessageType.GENERATED_KEYS, generatedKeys);
                }
                warnings = collectWarnings(st);
            } finally {
                if (owns && !retained) {
                    LogicalSession.closeQuietly(st);
                }
            }
            // release before the terminal frame so the pin is gone when the client sees EXECUTE_DONE
            session.maybeRelease();
            Messages.write(out, new ExecuteDone(warnings));
        } catch (SQLException e) {
            failure = e;
            closeCursorsOpenedSince(cursorsBefore);
            throw e;
        } catch (RuntimeException e) {
            closeCursorsOpenedSince(cursorsBefore);
            throw e;
        } finally {
            record(sql, start, rows, failure, pinnedBefore);
            session.maybeRelease();
        }
    }

    /**
     * An ERROR terminates the whole EXECUTE sequence (section 4.6): result items streamed before the failure are
     * discarded by the driver, so their cursors must not stay open (they would keep the session pinned until CLOSE).
     */
    private void closeCursorsOpenedSince(Set<Integer> before) {
        for (Cursor c : session.cursors()) {
            if (!before.contains(c.id())) {
                session.closeCursor(c.id());
            }
        }
    }

    /**
     * Advances to the next result while keeping the current (unexhausted, retained) result set open. Returns
     * {@code null} when the driver cannot do that: we then stop iterating rather than closing the cursor the client
     * just received.
     */
    private Boolean moreResultsKeepingCurrent(Statement st) {
        try {
            return st.getMoreResults(Statement.KEEP_CURRENT_RESULT);
        } catch (SQLException | UnsupportedOperationException | AbstractMethodError e) {
            LOG.trace("getMoreResults(KEEP_CURRENT_RESULT) unsupported: {}", e.toString());
            return null;
        }
    }

    private record Streamed(long rows, boolean retained, int cursorId) {
    }

    /**
     * Sends RESULT_SET_HEADER + the first ROWS frame. When the result set is exhausted it is closed; otherwise a
     * cursor is registered and the physical connection stays pinned.
     */
    private Streamed streamResultSet(Statement st, ResultSet rs, boolean ownsStatement, boolean outParam, int statementId,
                                     int fetch) throws SQLException, IOException {
        if (rs == null) {
            throw new SQLException("driver returned no result set", ErrorMessage.STATE_GENERAL);
        }
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<ColumnMeta> columns = new ArrayList<>(n);
        ValueTag[] tags = new ValueTag[n];
        for (int i = 1; i <= n; i++) {
            ColumnMeta cm = normalizeTemporal(ColumnMeta.from(md, i));
            columns.add(cm);
            tags[i - 1] = cm.valueTag();
        }
        int cursorId = session.allocateCursorId();
        Messages.write(out, new ResultSetHeader(cursorId, columns));
        RowsWriter w = new RowsWriter(cursorId, n);
        boolean more = true;
        try {
            while (w.rowCount() < fetch && w.sizeBytes() < rowsFrameSoftBytes && (more = rs.next())) {
                addRow(w, rs, tags);
            }
        } catch (SQLException e) {
            closeQuietly(rs);
            throw e;
        }
        boolean last = !more;
        writeRows(w, last, rs);
        if (last) {
            closeQuietly(rs);
            return new Streamed(w.rowCount(), false, cursorId);
        }
        session.registerCursor(new Cursor(cursorId, rs, st, tags, ownsStatement, outParam, statementId));
        return new Streamed(w.rowCount(), true, cursorId);
    }

    /**
     * Zoned temporal columns that the physical driver reports under a zone-less JDBC type or a vendor code: PostgreSQL
     * announces {@code timestamptz}/{@code timetz} as {@code TIMESTAMP}/{@code TIME}, Oracle uses {@code -101}/{@code -102},
     * SQL Server {@code -155} for {@code datetimeoffset}. Without this they would travel as local TIMESTAMP (losing the
     * offset) or as a vendor-formatted STRING; the JDBC type delivered to the driver is adjusted to match the tag.
     */
    static ColumnMeta normalizeTemporal(ColumnMeta cm) {
        int mapped = zonedJdbcType(cm.jdbcType(), cm.typeName());
        if (mapped == cm.jdbcType()) {
            return cm;
        }
        return new ColumnMeta(cm.label(), cm.name(), mapped, cm.typeName(), cm.precision(), cm.scale(), cm.nullable(),
                cm.className(), cm.tableName(), cm.schemaName(), cm.catalogName(), cm.signed(), cm.autoIncrement(),
                cm.caseSensitive(), cm.currency(), cm.readOnly(), cm.searchable(), cm.displaySize());
    }

    static int zonedJdbcType(int jdbcType, String typeName) {
        String name = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT).trim();
        if (jdbcType == ORACLE_TIMESTAMPTZ || jdbcType == ORACLE_TIMESTAMPLTZ || jdbcType == MSSQL_DATETIMEOFFSET) {
            return Types.TIMESTAMP_WITH_TIMEZONE;
        }
        if (jdbcType == Types.TIMESTAMP || jdbcType == Types.OTHER) {
            if (name.equals("timestamptz") || name.equals("datetimeoffset") || name.contains("with time zone")
                    || name.contains("with local time zone")) {
                return Types.TIMESTAMP_WITH_TIMEZONE;
            }
        }
        if (jdbcType == Types.TIME || jdbcType == Types.OTHER) {
            if (name.equals("timetz") || name.startsWith("time") && name.contains("with time zone")) {
                return Types.TIME_WITH_TIMEZONE;
            }
        }
        return jdbcType;
    }

    /**
     * Appends the current row. TIME columns are read explicitly as {@link LocalTime} ({@code getObject(i, LocalTime.class)},
     * falling back to {@code getTime} converted through its instant so the millisecond part survives). The codec's
     * {@code Values.encodeColumn} applies the same strategy nowadays; the gateway keeps its own path so that the row
     * encoding does not depend on the codec's {@code ResultSet} reading strategy.
     */
    static void addRow(RowsWriter w, ResultSet rs, ValueTag[] tags) throws SQLException {
        if (tags.length == 0) {
            w.addEmptyRow();
            return;
        }
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == ValueTag.TIME) {
                w.addCell(readLocalTime(rs, i + 1));
            } else {
                w.addCell(rs, i + 1, tags[i]);
            }
        }
    }

    static LocalTime readLocalTime(ResultSet rs, int column) throws SQLException {
        try {
            return rs.getObject(column, LocalTime.class);
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
            java.sql.Time t = rs.getTime(column);
            return t == null ? null : Instant.ofEpochMilli(t.getTime()).atZone(ZoneId.systemDefault()).toLocalTime();
        }
    }

    private void writeRows(RowsWriter w, boolean last, ResultSet rs) throws SQLException, IOException {
        try {
            w.writeTo(out, last);
        } catch (ProtocolException e) {
            // the frame exceeds the configured maximum: terminate the sequence with an ERROR
            closeQuietly(rs);
            throw new SQLException("result rows exceed the maximum frame size: " + e.getMessage(),
                    ErrorMessage.STATE_GENERAL, e);
        }
    }

    private boolean writeOutParams(CallableStatement cs, List<Execute.OutParam> outParams, boolean owns, int statementId,
                                   int fetch) throws SQLException, IOException {
        boolean retained = false;
        List<OutParams.Entry> entries = new ArrayList<>(outParams.size());
        for (Execute.OutParam op : outParams) {
            boolean cursorTyped = op.jdbcType() == Types.REF_CURSOR || op.jdbcType() == ORACLE_CURSOR;
            Object value = cursorTyped || op.jdbcType() == Types.OTHER || op.jdbcType() == Types.JAVA_OBJECT
                    ? cs.getObject(op.index()) : readOut(cs, op.index(), op.jdbcType());
            if (value instanceof ResultSet rs) {
                Streamed s = streamResultSet(cs, rs, owns, true, statementId, fetch);
                retained |= s.retained;
                entries.add(new OutParams.Entry(op.index(), s.cursorId()));
            } else {
                entries.add(new OutParams.Entry(op.index(), value));
            }
        }
        Messages.write(out, new OutParams(entries));
        return retained;
    }

    /** Reads an OUT value with the getter matching the registered type (mirrors {@link Values#encodeColumn}). */
    static Object readOut(CallableStatement cs, int index, int jdbcType) throws SQLException {
        ValueTag tag = Values.tagForJdbcType(zonedJdbcType(jdbcType, null));
        Object v = switch (tag) {
            case NULL, TYPED_NULL -> cs.getObject(index);
            case BOOLEAN -> cs.getBoolean(index);
            case BYTE -> cs.getByte(index);
            case SHORT -> cs.getShort(index);
            case INT -> cs.getInt(index);
            case LONG -> cs.getLong(index);
            case FLOAT -> cs.getFloat(index);
            case DOUBLE -> cs.getDouble(index);
            case DECIMAL -> cs.getBigDecimal(index);
            case STRING -> jdbcType == Types.OTHER ? cs.getObject(index) : cs.getString(index);
            case BYTES -> cs.getBytes(index);
            case DATE -> cs.getDate(index);
            case TIME -> {
                try {
                    yield cs.getObject(index, LocalTime.class);
                } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
                    java.sql.Time t = cs.getTime(index);
                    yield t == null ? null : Instant.ofEpochMilli(t.getTime()).atZone(ZoneId.systemDefault()).toLocalTime();
                }
            }
            case TIMESTAMP -> cs.getTimestamp(index);
            case TIMESTAMP_TZ -> {
                try {
                    yield cs.getObject(index, OffsetDateTime.class);
                } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
                    yield cs.getTimestamp(index);
                }
            }
            case TIME_TZ -> {
                try {
                    yield cs.getObject(index, OffsetTime.class);
                } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
                    yield cs.getTime(index);
                }
            }
        };
        if (v == null || cs.wasNull()) {
            return null;
        }
        return v;
    }

    /** Materialises the GENERATED_KEYS payload (small by nature) or returns {@code null} when unavailable. */
    private static byte[] generatedKeysPayload(Statement st) {
        ResultSet ks;
        try {
            ks = st.getGeneratedKeys();
        } catch (SQLException | UnsupportedOperationException e) {
            LOG.debug("getGeneratedKeys not available: {}", e.toString());
            return null;
        }
        if (ks == null) {
            return null;
        }
        try {
            ResultSetMetaData md = ks.getMetaData();
            int n = md.getColumnCount();
            ProtocolOutput po = new ProtocolOutput();
            po.writeI32(n);
            ValueTag[] tags = new ValueTag[n];
            for (int i = 1; i <= n; i++) {
                ColumnMeta cm = normalizeTemporal(ColumnMeta.from(md, i));
                cm.encode(po);
                tags[i - 1] = cm.valueTag();
            }
            int countPos = po.reserveI32();
            int rows = 0;
            while (ks.next()) {
                for (int i = 1; i <= n; i++) {
                    Values.encodeColumn(po, ks, i, tags[i - 1]);
                }
                rows++;
            }
            po.putI32At(countPos, rows);
            return po.toByteArray();
        } catch (SQLException e) {
            LOG.debug("reading generated keys failed: {}", e.toString());
            return null;
        } finally {
            closeQuietly(ks);
        }
    }

    // ------------------------------------------------------------------ FETCH

    public void fetch(Fetch req) throws SQLException, IOException {
        session.touch();
        Cursor cur = session.cursor(req.cursorId());
        int n = req.maxRows() <= 0 ? ProtocolConstants.DEFAULT_FETCH_SIZE : req.maxRows();
        RowsWriter w = new RowsWriter(cur.id(), cur.columnCount());
        boolean more = true;
        try {
            ResultSet rs = cur.resultSet();
            while (w.rowCount() < n && w.sizeBytes() < rowsFrameSoftBytes && (more = rs.next())) {
                addRow(w, rs, cur.tags());
            }
        } catch (SQLException e) {
            session.closeCursor(cur.id()); // the physical driver gave up on the cursor (e.g. after COMMIT)
            throw e;
        }
        boolean last = !more;
        if (last) {
            session.closeCursor(cur.id());
        }
        try {
            w.writeTo(out, last);
        } catch (ProtocolException e) {
            session.closeCursor(cur.id());
            throw new SQLException("result rows exceed the maximum frame size: " + e.getMessage(),
                    ErrorMessage.STATE_GENERAL, e);
        }
    }

    // ------------------------------------------------------------------ EXECUTE_BATCH

    public void executeBatch(ExecuteBatch req) throws SQLException, IOException {
        long start = System.nanoTime();
        boolean pinnedBefore = session.isPinned();
        PreparedEntry entry = req.statementId() >= 0 ? session.prepared(req.statementId()) : null;
        boolean plain = entry == null && req.kind() == StatementKind.STATEMENT;
        String sql = entry != null ? entry.sql() : req.sql() != null ? req.sql() : String.join(";\n", req.sqls());
        long rows = -1;
        SQLException failure = null;
        try {
            if (plain) {
                for (String s : req.sqls()) {
                    enforceReadOnly(s);
                }
            } else {
                enforceReadOnly(sql);
            }
            Connection c = session.acquire();
            session.noteStatementAttempt();
            long[] counts;
            List<Warning> warnings;
            if (plain) {
                try (Statement st = c.createStatement()) {
                    for (String s : req.sqls()) {
                        st.addBatch(s);
                    }
                    try {
                        counts = executeLargeBatch(st);
                    } finally {
                        session.noteWork();
                    }
                    warnings = collectWarnings(st);
                }
            } else {
                if (sql == null) {
                    throw new SQLException("EXECUTE_BATCH without sql or registered statement", ErrorMessage.STATE_GENERAL);
                }
                PreparedStatement ps;
                boolean owns;
                if (entry != null) {
                    ps = session.physicalStatement(entry, entry.autoGeneratedKeys(), entry.keyColumns());
                    owns = false;
                } else {
                    ps = LogicalSession.createStatement(c, sql, req.kind(), Statement.NO_GENERATED_KEYS, List.of());
                    owns = true;
                }
                try {
                    ps.clearBatch();
                    for (List<Object> set : req.paramSets()) {
                        bindParams(ps, set);
                        ps.addBatch();
                    }
                    try {
                        counts = executeLargeBatch(ps);
                    } finally {
                        session.noteWork();
                    }
                    warnings = collectWarnings(ps);
                } finally {
                    if (owns) {
                        LogicalSession.closeQuietly(ps);
                    } else {
                        try {
                            ps.clearBatch();
                        } catch (SQLException | RuntimeException ignored) {
                            // best effort
                        }
                    }
                }
            }
            rows = 0;
            for (long cnt : counts) {
                if (cnt >= 0) {
                    rows += cnt;
                }
            }
            session.maybeRelease();
            Messages.write(out, new BatchResult(counts, warnings));
        } catch (SQLException e) {
            failure = e;
            throw e;
        } finally {
            record(sql, start, rows, failure, pinnedBefore);
            session.maybeRelease();
        }
    }

    private static long[] executeLargeBatch(Statement st) throws SQLException {
        try {
            return st.executeLargeBatch();
        } catch (BatchUpdateException e) {
            throw e;
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError e) {
            int[] small = st.executeBatch();
            long[] counts = new long[small.length];
            for (int i = 0; i < small.length; i++) {
                counts[i] = small[i];
            }
            return counts;
        }
    }

    // ------------------------------------------------------------------ METADATA

    public void metadata(Metadata req) throws SQLException, IOException {
        session.touch();
        if (!METADATA_OPERATIONS.contains(req.operation())) {
            throw new SQLException("unsupported metadata operation '" + req.operation() + "'",
                    ErrorMessage.STATE_NOT_SUPPORTED);
        }
        try {
            Connection c = session.acquire();
            DatabaseMetaData md = c.getMetaData();
            Method method = findMethod(req.operation(), req.args().size());
            Object[] args = convertArgs(method, req.args());
            ResultSet rs;
            try {
                rs = (ResultSet) method.invoke(md, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof SQLException se) {
                    throw se;
                }
                throw new SQLException("metadata call failed: " + e.getCause(), ErrorMessage.STATE_GENERAL, e.getCause());
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new SQLException("metadata call failed: " + e, ErrorMessage.STATE_GENERAL, e);
            }
            Statement st = null;
            try {
                st = rs.getStatement();
            } catch (SQLException | RuntimeException ignored) {
                // some drivers return result sets without a statement
            }
            Streamed s;
            try {
                s = streamResultSet(st, rs, st != null, false, ProtocolConstants.DIRECT_STATEMENT_ID,
                        ProtocolConstants.DEFAULT_FETCH_SIZE);
            } catch (SQLException | IOException | RuntimeException e) {
                closeQuietly(rs);
                LogicalSession.closeQuietly(st);
                throw e;
            }
            if (!s.retained) {
                // the physical driver's metadata statement holds a server cursor on Oracle: free it with the result
                LogicalSession.closeQuietly(st);
            }
            session.maybeRelease();
            Messages.write(out, ExecuteDone.NO_WARNINGS);
        } finally {
            session.maybeRelease();
        }
    }

    private static Method findMethod(String name, int argCount) throws SQLException {
        for (Method m : DatabaseMetaData.class.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount && m.getReturnType() == ResultSet.class) {
                return m;
            }
        }
        throw new SQLException("metadata operation '" + name + "' does not take " + argCount + " arguments",
                ErrorMessage.STATE_NOT_SUPPORTED);
    }

    private static Object[] convertArgs(Method m, List<Object> args) throws SQLException {
        Class<?>[] types = m.getParameterTypes();
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object a = args.get(i);
            Class<?> t = types[i];
            if (a == null || a instanceof TypedNull) {
                if (t.isPrimitive()) {
                    throw new SQLException("argument " + (i + 1) + " of " + m.getName() + " must not be null",
                            ErrorMessage.STATE_GENERAL);
                }
                out[i] = null;
            } else if (t == String.class) {
                out[i] = a.toString();
            } else if (t == boolean.class) {
                out[i] = a instanceof Boolean b ? b : Boolean.parseBoolean(a.toString());
            } else if (t == int.class) {
                out[i] = a instanceof Number n ? n.intValue() : Integer.parseInt(a.toString());
            } else if (t == String[].class) {
                out[i] = Metadata.splitStringArray(a.toString());
            } else if (t == int[].class) {
                String[] parts = Metadata.splitStringArray(a.toString());
                int[] ints = new int[parts.length];
                for (int j = 0; j < parts.length; j++) {
                    ints[j] = Integer.parseInt(parts[j].trim());
                }
                out[i] = ints;
            } else {
                out[i] = a;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Read-only grant: writes are rejected by the gateway before the physical connection is touched, for every engine,
     * with SQLState {@link #STATE_READ_ONLY}. {@code Connection.setReadOnly(true)} on the physical connection remains
     * the second line of defence for what the analyzer cannot classify (routines, anonymous blocks).
     */
    private void enforceReadOnly(String sql) throws SQLException {
        if (sql == null || !session.isGrantReadOnly()) {
            return;
        }
        SqlOperation op = telemetry.analyzer().analyze(sql, session.engine()).operation();
        if (WRITE_OPERATIONS.contains(op)) {
            throw new SQLException("read-only datasource grant: " + op + " statements are not allowed", STATE_READ_ONLY);
        }
    }

    private void applyOptions(Statement st, Execute.ExecOptions opt) throws SQLException {
        // always applied, also when 0: the statement may be a cached PreparedStatement still carrying the maxRows /
        // query timeout of its previous execution
        st.setMaxRows(Math.max(0, opt.maxRows()));
        int timeout = session.effectiveQueryTimeout(opt.queryTimeoutSeconds());
        try {
            st.setQueryTimeout(timeout);
        } catch (SQLException | UnsupportedOperationException e) {
            LOG.trace("setQueryTimeout unsupported: {}", e.toString());
        }
        if (opt.fetchSize() > 0) {
            try {
                st.setFetchSize(opt.fetchSize());
            } catch (SQLException | UnsupportedOperationException e) {
                LOG.trace("setFetchSize unsupported: {}", e.toString());
            }
        }
    }

    private static int fetchSize(int requested) {
        return requested <= 0 ? ProtocolConstants.DEFAULT_FETCH_SIZE : requested;
    }

    private static long updateCount(Statement st) throws SQLException {
        try {
            return st.getLargeUpdateCount();
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError e) {
            return st.getUpdateCount();
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        bindParams(ps, params, Set.of());
    }

    /**
     * Binds the parameters. A NULL (or typed null) at an index registered as OUT parameter is the placeholder the
     * driver sends for a pure OUT parameter and is not bound; INOUT parameters carry a real value and are bound.
     */
    private void bindParams(PreparedStatement ps, List<Object> params, Set<Integer> registeredOut) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if ((v == null || v instanceof TypedNull) && registeredOut.contains(i + 1)) {
                continue;
            }
            bind(ps, i + 1, v);
        }
    }

    /** Binds one decoded wire value with the JDBC setter matching its Java type. */
    void bind(PreparedStatement ps, int idx, Object v) throws SQLException {
        switch (v) {
            case null -> bindNull(ps, idx);
            case TypedNull tn -> ps.setNull(idx, tn.jdbcType());
            case Boolean b -> ps.setBoolean(idx, b);
            case Byte b -> ps.setByte(idx, b);
            case Short s -> ps.setShort(idx, s);
            case Integer i -> ps.setInt(idx, i);
            case Long l -> ps.setLong(idx, l);
            case Float f -> ps.setFloat(idx, f);
            case Double d -> ps.setDouble(idx, d);
            case BigDecimal bd -> ps.setBigDecimal(idx, bd);
            case String s -> ps.setString(idx, s);
            case byte[] b -> ps.setBytes(idx, b);
            case LocalDate d -> ps.setDate(idx, java.sql.Date.valueOf(d));
            case LocalTime t -> {
                try {
                    ps.setObject(idx, t); // java.sql.Time.valueOf(LocalTime) would drop the sub-second part
                } catch (SQLException | UnsupportedOperationException e) {
                    ps.setTime(idx, new java.sql.Time(java.sql.Timestamp.valueOf(LocalDate.EPOCH.atTime(t)).getTime()));
                }
            }
            case LocalDateTime ldt -> ps.setTimestamp(idx, java.sql.Timestamp.valueOf(ldt));
            case OffsetDateTime odt -> {
                try {
                    ps.setObject(idx, odt);
                } catch (SQLException | UnsupportedOperationException e) {
                    ps.setTimestamp(idx, java.sql.Timestamp.from(odt.toInstant()));
                }
            }
            case OffsetTime ot -> {
                try {
                    ps.setObject(idx, ot);
                } catch (SQLException | UnsupportedOperationException e) {
                    ps.setTime(idx, java.sql.Time.valueOf(ot.toLocalTime()));
                }
            }
            default -> ps.setObject(idx, v);
        }
    }

    private void bindNull(PreparedStatement ps, int idx) throws SQLException {
        switch (session.engine()) {
            case ORACLE -> ps.setNull(idx, Types.VARCHAR);
            case POSTGRES -> ps.setNull(idx, Types.OTHER);
            default -> {
                try {
                    ps.setObject(idx, null);
                } catch (SQLException e) {
                    ps.setNull(idx, Types.VARCHAR);
                }
            }
        }
    }

    private static void registerOut(CallableStatement cs, Execute.OutParam op) throws SQLException {
        if (op.typeName() != null && !op.typeName().isBlank()) {
            cs.registerOutParameter(op.index(), op.jdbcType(), op.typeName());
        } else if (op.scale() >= 0) {
            cs.registerOutParameter(op.index(), op.jdbcType(), op.scale());
        } else {
            cs.registerOutParameter(op.index(), op.jdbcType());
        }
    }

    private static List<Warning> collectWarnings(Statement st) {
        List<Warning> list = new ArrayList<>();
        try {
            SQLWarning w = st.getWarnings();
            while (w != null && list.size() < MAX_WARNINGS) {
                list.add(Warning.from(w));
                w = w.getNextWarning();
            }
            st.clearWarnings();
        } catch (SQLException | RuntimeException ignored) {
            // warnings are optional
        }
        return list;
    }

    private void record(String sql, long startNanos, long rows, SQLException failure, boolean pinnedBefore) {
        try {
            telemetry.recordStatement(session.facts(),
                    new GatewayTelemetry.StatementOutcome(sql, System.nanoTime() - startNanos, rows, failure, pinnedBefore));
        } catch (RuntimeException e) {
            LOG.debug("telemetry failed: {}", e.toString());
        }
    }

    private static void closeQuietly(ResultSet rs) {
        if (rs == null) {
            return;
        }
        try {
            rs.close();
        } catch (SQLException | RuntimeException ignored) {
            // best effort
        }
    }
}
