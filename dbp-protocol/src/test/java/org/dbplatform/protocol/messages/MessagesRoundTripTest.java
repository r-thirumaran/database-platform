package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;
import org.dbplatform.protocol.TypedNull;
import org.dbplatform.protocol.Warning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagesRoundTripTest {

    static final ColumnMeta COL_ID = new ColumnMeta("ID", "id", Types.NUMERIC, "NUMBER", 38, 0, ColumnMeta.NO_NULLS,
            "java.math.BigDecimal", "ORDERS", "RETAIL", null, true, true, false, false, false, true, 40);
    static final ColumnMeta COL_NAME = new ColumnMeta("Customer Name", "NAME", Types.VARCHAR, "VARCHAR2", 200, 0,
            ColumnMeta.NULLABLE, "java.lang.String", "ORDERS", "RETAIL", "", false, false, true, false, true, true, 200);
    static final ColumnMeta COL_UNKNOWN = new ColumnMeta(null, null, Types.OTHER, null, -1, -1,
            ColumnMeta.NULLABLE_UNKNOWN, null, null, null, null, false, false, false, true, false, false, Integer.MIN_VALUE);

    static final Warning W1 = new Warning("01000", 1234, "generic warning ü");
    static final Warning W2 = new Warning(null, 0, null);

    static Map<String, String> props() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Hello.PROP_DATASOURCE, "sales");
        m.put(Hello.PROP_API_KEY, "dbp_abc_s3cr3t");
        m.put(Hello.PROP_AUTOCOMMIT, "false");
        m.put("clientInfo.ApplicationName", "demo 日本");
        m.put("nullValue", null);
        return m;
    }

    static List<Object> params() {
        return Arrays.asList(1, "two", null, new BigDecimal("3.50"), new TypedNull(Types.DATE), new byte[] {4},
                LocalDate.of(2024, 1, 2), LocalDateTime.of(2024, 1, 2, 3, 4, 5), true, (short) 7, 8L, 9.5f, 10.25d);
    }

    /** One instance of every message in the catalogue, each exercising nullable / empty / extreme fields. */
    static Stream<Message> allMessages() {
        return Stream.of(
                Hello.of("dbp-jdbc", "0.1.0", props()),
                new Hello(1, null, null, Map.of()),
                Ping.INSTANCE,
                Close.INSTANCE,
                new Prepare("select * from orders where id = ?", StatementKind.PREPARED, Statement.RETURN_GENERATED_KEYS,
                        List.of("ID", "VERSION")),
                Prepare.of("{call p(?)}", StatementKind.CALLABLE),
                new Execute(ProtocolConstants.DIRECT_STATEMENT_ID, "select 1", StatementKind.STATEMENT, List.of(),
                        Execute.ExecOptions.DEFAULT, List.of()),
                new Execute(17, null, StatementKind.CALLABLE, params(),
                        new Execute.ExecOptions(Integer.MAX_VALUE, -1, 30, Execute.Expect.QUERY, Statement.RETURN_GENERATED_KEYS,
                                Arrays.asList("ID", null)),
                        List.of(new Execute.OutParam(1, Types.NUMERIC, 2, null), new Execute.OutParam(3, Types.STRUCT, -1, "MY_TYPE"))),
                Execute.direct("update t set x = ?", StatementKind.PREPARED, Arrays.asList((Object) null),
                        Execute.ExecOptions.DEFAULT.withExpect(Execute.Expect.UPDATE)),
                Execute.prepared(5, StatementKind.PREPARED, List.of(1), null),
                new Fetch(Integer.MIN_VALUE, 0),
                new Fetch(3, 500),
                new CloseCursor(-1),
                new CloseStatement(Integer.MAX_VALUE),
                ExecuteBatch.ofStatements(List.of("insert into a values (1)", "delete from b")),
                ExecuteBatch.ofPrepared(9, StatementKind.PREPARED, List.of(params(), List.of(), Arrays.asList(null, "x"))),
                new ExecuteBatch(ProtocolConstants.DIRECT_STATEMENT_ID, "insert into t values (?)", StatementKind.PREPARED,
                        List.of(List.of(1), List.of(2)), List.of()),
                new SetAutoCommit(true),
                new SetAutoCommit(false),
                Commit.INSTANCE,
                Rollback.FULL,
                new Rollback("sp1"),
                new SetSavepoint(null),
                new SetSavepoint("named"),
                new ReleaseSavepoint("named"),
                new SetTransactionIsolation(java.sql.Connection.TRANSACTION_SERIALIZABLE),
                new SetReadOnly(true),
                new SetSchema("RETAIL"),
                new SetSchema(null),
                new SetCatalog("db"),
                new SetClientInfo("ApplicationName", "app"),
                new SetClientInfo("ClientUser", null),
                new SetNetworkTimeout(0),
                new SetNetworkTimeout(30_000),
                Metadata.of("getTables", null, "RETAIL", "%", Metadata.joinStringArray(new String[] {"TABLE", "VIEW"})),
                Metadata.of("getCatalogs"),
                Metadata.of("getBestRowIdentifier", null, null, "T", 1, false),
                Ok.INSTANCE,
                new ErrorMessage("08006", -1, "connection lost", true),
                new ErrorMessage(null, 0, null, false),
                ErrorMessage.of(ErrorMessage.STATE_POLICY, "blocked"),
                new HelloOk("sess-1", "0.1.0", HelloOk.ENGINE_POSTGRES, Map.of("supportsSavepoints", "true", "url", "jdbc:dbp://g/sales")),
                new HelloOk(null, null, null, Map.of()),
                Pong.INSTANCE,
                new Prepared(1, 3),
                new Prepared(Integer.MAX_VALUE, ProtocolConstants.UNKNOWN_PARAMETER_COUNT),
                new ResultSetHeader(7, List.of(COL_ID, COL_NAME, COL_UNKNOWN)),
                new ResultSetHeader(8, List.of()),
                new Rows(7, List.of(Arrays.asList(new BigDecimal("1"), "a", null), Arrays.asList(null, null, null)), false),
                new Rows(7, List.of(), true),
                new UpdateCount(Long.MAX_VALUE),
                new UpdateCount(-2),
                new OutParams(List.of(new OutParams.Entry(1, new BigDecimal("42.00")), new OutParams.Entry(3, null),
                        new OutParams.Entry(Integer.MAX_VALUE, "s"))),
                new OutParams(List.of()),
                ExecuteDone.NO_WARNINGS,
                new ExecuteDone(List.of(W1, W2)),
                new GeneratedKeys(List.of(COL_ID), List.of(List.of(new BigDecimal("101")), List.of(new BigDecimal("102")))),
                new GeneratedKeys(List.of(), List.of()),
                new BatchResult(new long[] {1, 0, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED, Long.MAX_VALUE}, List.of(W1)),
                new BatchResult(new long[0], List.of()),
                new SavepointSet("SP_1"),
                new SavepointSet(null));
    }

    private static int columnCountOf(Message m) {
        return m instanceof Rows r && !r.rows().isEmpty() ? r.rows().get(0).size() : 3;
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    void payloadRoundTripsThroughRecordDecoderAndConsumesEveryByte(Message m) throws Exception {
        byte[] payload = m.encode();
        ProtocolInput in = new ProtocolInput(payload);
        Message decoded;
        if (m instanceof Rows) {
            decoded = Rows.decode(in, columnCountOf(m));
        } else {
            // every record exposes static decode(ProtocolInput)
            decoded = (Message) m.getClass().getMethod("decode", ProtocolInput.class).invoke(null, in);
        }
        in.expectEnd();
        assertEquivalent(decoded, m);
        assertThat(decoded.type()).isEqualTo(m.type());
        assertThat(decoded.encode()).isEqualTo(payload);

        // and through the dispatcher
        assertEquivalent(Messages.decode(new Frame(m.type(), payload), columnCountOf(m)), m);
        if (!(m instanceof Rows)) {
            assertEquivalent(Messages.decode(m.type(), payload), m);
        }
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    void frameRoundTripsThroughStreams(Message m) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FrameWriter w = new FrameWriter(bos);
        Messages.write(w, m);
        w.writeFrame(m.toFrame());
        FrameReader r = new FrameReader(new ByteArrayInputStream(bos.toByteArray()));
        Message a = Messages.read(r, columnCountOf(m));
        Frame f = r.readFrame();
        assertThat(f.type()).isEqualTo(m.type());
        Message b = Messages.decode(f, columnCountOf(m));
        assertEquivalent(a, m);
        assertEquivalent(b, m);
    }

    @Test
    void everyMessageTypeHasExactlyOneRecord() {
        EnumSet<MessageType> covered = EnumSet.noneOf(MessageType.class);
        allMessages().forEach(m -> covered.add(m.type()));
        assertThat(covered).containsExactlyInAnyOrder(MessageType.values());

        Class<?>[] permitted = Message.class.getPermittedSubclasses();
        assertThat(permitted).hasSize(MessageType.values().length);
        for (Class<?> c : permitted) {
            assertThat(c.isRecord()).as("%s is a record", c).isTrue();
        }
    }

    @Test
    void emptyPayloadMessagesEncodeToZeroBytes() {
        for (Message m : List.of(Ping.INSTANCE, Close.INSTANCE, Commit.INSTANCE, Ok.INSTANCE, Pong.INSTANCE)) {
            assertThat(m.encode()).isEmpty();
            assertThat(m.toFrame().wireLength()).isEqualTo(1);
        }
    }

    @Test
    void rowsDispatchRequiresColumnCount() {
        Frame rows = new Rows(1, List.of(List.of(1)), true).toFrame();
        assertThatThrownBy(() -> Messages.decode(rows)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("column count");
        assertThatThrownBy(() -> Messages.read(new FrameReader(new ByteArrayInputStream(frameBytes(rows)))))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void rowsWriterProducesTheSamePayloadAsTheRecord() throws ProtocolException {
        List<List<Object>> data = List.of(
                Arrays.asList(1, "a", null),
                Arrays.asList(2, "b", new BigDecimal("2.5")),
                Arrays.asList(3, "", new BigDecimal("-0.001")));
        Rows record = new Rows(42, data, false);

        RowsWriter viaRows = new RowsWriter(42, 3);
        viaRows.addRow(data.get(0)).addRow(2, "b", new BigDecimal("2.5"));
        for (Object cell : data.get(2)) {
            viaRows.addCell(cell);
        }
        assertThat(viaRows.rowCount()).isEqualTo(3);
        assertThat(viaRows.sizeBytes()).isGreaterThan(8);
        byte[] payload = viaRows.finish(false);
        assertThat(payload).isEqualTo(record.encode());

        Rows decoded = Rows.decode(new ProtocolInput(payload), 3);
        assertThat(decoded.cursorId()).isEqualTo(42);
        assertThat(decoded.last()).isFalse();
        assertThat(decoded.rows()).hasSize(3);
        assertThat(decoded.rows().get(1)).containsExactly(2, "b", new BigDecimal("2.5"));

        RowsWriter last = new RowsWriter(42, 3);
        assertThat(last.toFrame(true)).isEqualTo(Rows.endOf(42).toFrame());
    }

    @Test
    void rowsWriterStreamsToFrameWriterAndGuardsMisuse() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RowsWriter w = new RowsWriter(5, 2);
        w.addRow("x", 1).addCell("y");
        assertThatThrownBy(() -> w.finish(true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> w.addRow("z", 2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> w.addEmptyRow()).isInstanceOf(IllegalStateException.class);
        w.addCell(2);
        assertThatThrownBy(() -> w.addRow("only one")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.addRow(List.of(1, 2, 3))).isInstanceOf(IllegalArgumentException.class);
        w.writeTo(new FrameWriter(bos), true);
        assertThatThrownBy(() -> w.addCell(1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("finished");
        assertThatThrownBy(() -> w.finish(true)).isInstanceOf(IllegalStateException.class);

        Frame f = new FrameReader(new ByteArrayInputStream(bos.toByteArray())).readFrame();
        Rows rows = (Rows) Messages.decode(f, 2);
        assertThat(rows).isEqualTo(new Rows(5, List.of(List.of("x", 1), List.of("y", 2)), true));
        assertThat(rows.rowCount()).isEqualTo(2);

        assertThatThrownBy(() -> new RowsWriter(1, -1)).isInstanceOf(IllegalArgumentException.class);
        RowsWriter zero = new RowsWriter(1, 0);
        assertThatThrownBy(() -> zero.addCell(1)).isInstanceOf(IllegalStateException.class);
        zero.addEmptyRow().addEmptyRow();
        Rows zeroRows = Rows.decode(new ProtocolInput(zero.finish(true)), 0);
        assertThat(zeroRows.rowCount()).isEqualTo(2);
        assertThat(zeroRows.rows()).allSatisfy(r -> assertThat(r).isEmpty());
    }

    @Test
    void rowsAndGeneratedKeysValidateRowWidth() {
        assertThatThrownBy(() -> new Rows(1, List.of(List.of(1), List.of(1, 2)), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeneratedKeys(List.of(COL_ID), List.of(List.of(1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Rows(1, null, true).rows()).isEmpty();
    }

    @Test
    void decodersRejectMalformedPayloads() {
        // Rows with more rows than bytes
        byte[] bogusRows = new ProtocolOutput().writeI32(1).writeI32(1_000_000).writeU8(0).toByteArray();
        assertThatThrownBy(() -> Rows.decode(new ProtocolInput(bogusRows), 2)).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> Rows.decode(new ProtocolInput(bogusRows), -1)).isInstanceOf(ProtocolException.class);
        byte[] zeroColHuge = new ProtocolOutput().writeI32(1).writeI32(Integer.MAX_VALUE).writeU8(1).toByteArray();
        assertThatThrownBy(() -> Rows.decode(new ProtocolInput(zeroColHuge), 0)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("implausible");

        // ResultSetHeader with huge column count
        byte[] bogusHeader = new ProtocolOutput().writeI32(1).writeI32(Integer.MAX_VALUE).toByteArray();
        assertThatThrownBy(() -> ResultSetHeader.decode(new ProtocolInput(bogusHeader))).isInstanceOf(ProtocolException.class);

        // ColumnMeta with invalid nullable
        ProtocolOutput badMeta = new ProtocolOutput();
        new ColumnMeta("l", "n", 1, "t", 0, 0, 0, null, null, null, null, false, false, false, false, false, false, 0).encode(badMeta);
        byte[] bytes = badMeta.toByteArray();
        bytes[4 + 1 + 4 + 1 + 4 + 4 + 1 + 4 + 4] = 3; // nullable byte
        assertThatThrownBy(() -> ColumnMeta.decode(new ProtocolInput(bytes))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("nullable");

        // kind / expect bytes out of range
        byte[] badKind = new ProtocolOutput().writeString("sql").writeU8(3).writeI32(2).writeStringArray(List.of()).toByteArray();
        assertThatThrownBy(() -> Prepare.decode(new ProtocolInput(badKind))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("statement kind");
        byte[] badExpect = new ProtocolOutput().writeI32(0).writeI32(0).writeI32(0).writeU8(9).writeI32(2)
                .writeStringArray(List.of()).toByteArray();
        assertThatThrownBy(() -> Execute.ExecOptions.decode(new ProtocolInput(badExpect))).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("expect");

        // required strings missing
        byte[] nullSql = new ProtocolOutput().writeString(null).writeU8(0).writeI32(2).writeStringArray(List.of()).toByteArray();
        assertThatThrownBy(() -> Prepare.decode(new ProtocolInput(nullSql))).isInstanceOf(ProtocolException.class);
        byte[] directWithoutSql = new ProtocolOutput().writeI32(-1).writeString(null).writeU8(0).writeValues(List.of())
                .writeI32(0).writeI32(0).writeI32(0).writeU8(0).writeI32(2).writeStringArray(List.of()).writeI32(0).toByteArray();
        assertThatThrownBy(() -> Execute.decode(new ProtocolInput(directWithoutSql))).isInstanceOf(ProtocolException.class);
        byte[] nullOperation = new ProtocolOutput().writeString(null).writeValues(List.of()).toByteArray();
        assertThatThrownBy(() -> Metadata.decode(new ProtocolInput(nullOperation))).isInstanceOf(ProtocolException.class);
        byte[] nullClientInfoName = new ProtocolOutput().writeString(null).writeString("v").toByteArray();
        assertThatThrownBy(() -> SetClientInfo.decode(new ProtocolInput(nullClientInfoName))).isInstanceOf(ProtocolException.class);

        // truncated anything
        assertThatThrownBy(() -> Messages.decode(MessageType.HELLO, new byte[] {0})).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> Messages.decode(MessageType.ERROR, new byte[0])).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> Messages.decode(MessageType.BATCH_RESULT, new byte[] {0, 0, 0, 5})).isInstanceOf(ProtocolException.class);
    }

    @Test
    void trailingBytesAreToleratedByTheDispatcher() throws ProtocolException {
        byte[] payload = new Fetch(1, 2).encode();
        byte[] extended = Arrays.copyOf(payload, payload.length + 3);
        assertThat(Messages.decode(MessageType.FETCH, extended)).isEqualTo(new Fetch(1, 2));
    }

    @Test
    void recordsAreDefensiveAndImmutable() {
        List<Object> params = new ArrayList<>(List.of(1));
        Execute e = Execute.direct("s", StatementKind.PREPARED, params, null);
        params.add(2);
        assertThat(e.params()).containsExactly(1);
        assertThatThrownBy(() -> e.params().add(3)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(e.options()).isSameAs(Execute.ExecOptions.DEFAULT);
        assertThat(e.isDirect()).isTrue();
        assertThat(Execute.prepared(1, StatementKind.PREPARED, null, null).isDirect()).isFalse();
        assertThatThrownBy(() -> new Execute(-1, null, StatementKind.STATEMENT, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Execute(1, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Prepare(null, StatementKind.PREPARED, 2, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SetClientInfo(null, "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Metadata(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Execute.ExecOptions(0, 0, 0, null, 2, null)).isInstanceOf(IllegalArgumentException.class);

        long[] counts = {1, 2};
        BatchResult br = new BatchResult(counts, null);
        counts[0] = 99;
        assertThat(br.updateCounts()).containsExactly(1, 2);
        br.updateCounts()[0] = 77;
        assertThat(br.updateCounts()).containsExactly(1, 2);
        assertThat(br).isEqualTo(new BatchResult(new long[] {1, 2}, List.of())).hasSameHashCodeAs(new BatchResult(new long[] {1, 2}, List.of()));
        assertThat(br.toString()).contains("[1, 2]");
        assertThat(new BatchResult(new long[] {Long.MAX_VALUE, 5, Long.MIN_VALUE}, null).intUpdateCounts())
                .containsExactly(Statement.SUCCESS_NO_INFO, 5, Statement.SUCCESS_NO_INFO);

        Map<String, String> p = new LinkedHashMap<>(Map.of("a", "b"));
        Hello h = Hello.of("c", "v", p);
        p.put("x", "y");
        assertThat(h.properties()).hasSize(1);
        assertThat(h.protocolVersion()).isEqualTo(ProtocolConstants.VERSION);
    }

    @Test
    void convenienceAccessors() {
        assertThat(Rollback.FULL.isFull()).isTrue();
        assertThat(new Rollback("sp").isFull()).isFalse();
        assertThat(new ResultSetHeader(1, List.of(COL_ID, COL_NAME)).columnCount()).isEqualTo(2);
        assertThat(new GeneratedKeys(List.of(COL_ID), List.of()).columnCount()).isEqualTo(1);
        assertThat(new OutParams(List.of(new OutParams.Entry(2, "b"), new OutParams.Entry(1, "a"))).asMap())
                .containsExactly(Map.entry(2, "b"), Map.entry(1, "a"));
        HelloOk ok = new HelloOk("s", "v", HelloOk.ENGINE_H2, Map.of("supportsSavepoints", "true", "maxConnections", "12", "bad", "x"));
        assertThat(ok.booleanProperty("supportsSavepoints", false)).isTrue();
        assertThat(ok.booleanProperty("missing", true)).isTrue();
        assertThat(ok.intProperty("maxConnections", 0)).isEqualTo(12);
        assertThat(ok.intProperty("bad", 7)).isEqualTo(7);
        assertThat(ok.intProperty("missing", 7)).isEqualTo(7);
        assertThat(ok.property("supportsSavepoints")).isEqualTo("true");
        assertThat(Execute.OutParam.of(2, Types.VARCHAR)).isEqualTo(new Execute.OutParam(2, Types.VARCHAR, ProtocolConstants.NO_SCALE, null));
        assertThat(COL_ID.valueTag()).isEqualTo(org.dbplatform.protocol.ValueTag.DECIMAL);
        assertThat(ColumnMeta.simple("c", Types.INTEGER, "INT").signed()).isTrue();
        assertThat(ColumnMeta.simple("c", Types.VARCHAR, "VARCHAR").signed()).isFalse();
        assertThatThrownBy(() -> new ColumnMeta("l", "n", 1, "t", 0, 0, 5, null, null, null, null, false, false, false, false, false, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataStringArrayPacking() {
        assertThat(Metadata.joinStringArray(null)).isNull();
        assertThat(Metadata.joinStringArray(new String[0])).isEmpty();
        assertThat(Metadata.joinStringArray(new String[] {"TABLE"})).isEqualTo("TABLE");
        assertThat(Metadata.joinStringArray(new String[] {"TABLE", "VIEW"})).isEqualTo("TABLE\u0000VIEW");
        assertThat(Metadata.splitStringArray(null)).isNull();
        assertThat(Metadata.splitStringArray("")).isEmpty();
        assertThat(Metadata.splitStringArray("TABLE\u0000VIEW\u0000")).containsExactly("TABLE", "VIEW", "");
        assertThat(Metadata.splitStringArray(Metadata.joinStringArray(new String[] {"a", "b"}))).containsExactly("a", "b");
    }

    @Test
    void errorMessageMapsToSqlExceptionSubclasses() {
        assertThat((Throwable) new ErrorMessage("08006", 0, "m", true).toSqlException()).isInstanceOf(SQLNonTransientConnectionException.class);
        assertThat((Throwable) new ErrorMessage("08001", 0, "m", false).toSqlException()).isInstanceOf(SQLTransientConnectionException.class);
        assertThat((Throwable) new ErrorMessage("0A000", 0, "m", false).toSqlException()).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThat((Throwable) new ErrorMessage("HY008", 0, "m", false).toSqlException()).isInstanceOf(SQLTimeoutException.class);
        assertThat((Throwable) new ErrorMessage("42000", 0, "m", false).toSqlException()).isInstanceOf(SQLSyntaxErrorException.class);
        SQLException plain = new ErrorMessage("HY000", 7, "m", false).toSqlException();
        assertThat((Throwable) plain).isExactlyInstanceOf(SQLException.class);
        assertThat(plain.getSQLState()).isEqualTo("HY000");
        assertThat(plain.getErrorCode()).isEqualTo(7);
        assertThat((Throwable) new ErrorMessage(null, 0, "m", false).toSqlException()).isExactlyInstanceOf(SQLException.class);
        assertThat((Throwable) new ErrorMessage("X", 0, "m", false).toSqlException()).isExactlyInstanceOf(SQLException.class);

        ErrorMessage from = ErrorMessage.from(new SQLSyntaxErrorException("bad sql", "42601", 5), false);
        assertThat(from).isEqualTo(new ErrorMessage("42601", 5, "bad sql", false));
        assertThat(ErrorMessage.from(new SQLException(), true).message()).isEqualTo("SQLException");
        assertThat(ErrorMessage.fatal("08006", "x").fatal()).isTrue();
        assertThat(W1.toSqlWarning().getSQLState()).isEqualTo("01000");
        assertThat(Warning.from(W1.toSqlWarning())).isEqualTo(W1);
    }

    @Test
    void sequenceOfAnExecuteExchangeDecodesWithHeaderDrivenColumnCount() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FrameWriter w = new FrameWriter(bos);
        ResultSetHeader header = new ResultSetHeader(1, List.of(COL_ID, COL_NAME));
        Messages.write(w, header);
        RowsWriter rows = new RowsWriter(1, 2);
        rows.addRow(new BigDecimal("1"), "alice").addRow(new BigDecimal("2"), null);
        rows.writeTo(w, false);
        Messages.write(w, new UpdateCount(3));
        Messages.write(w, ExecuteDone.NO_WARNINGS);

        FrameReader r = new FrameReader(new ByteArrayInputStream(bos.toByteArray()));
        int columnCount = -1;
        List<Message> seen = new ArrayList<>();
        while (true) {
            Message m = Messages.read(r, columnCount);
            seen.add(m);
            if (m instanceof ResultSetHeader h) {
                columnCount = h.columnCount();
            }
            if (m instanceof ExecuteDone) {
                break;
            }
        }
        assertThat(seen).hasSize(4);
        assertThat(seen.get(0)).isEqualTo(header);
        assertThat(seen.get(1)).isEqualTo(new Rows(1, List.of(List.of(new BigDecimal("1"), "alice"),
                Arrays.asList(new BigDecimal("2"), null)), false));
        assertThat(seen.get(2)).isEqualTo(new UpdateCount(3));
        assertThat(seen.get(3)).isEqualTo(ExecuteDone.NO_WARNINGS);
        assertThatThrownBy(r::readFrame).isInstanceOf(java.io.EOFException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] frameBytes(Frame f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new FrameWriter(bos).writeFrame(f);
        return bos.toByteArray();
    }

    /**
     * Record equality, except that {@code byte[]} cells inside parameter / row lists are compared by content
     * (arrays use identity equality in {@code List.equals}).
     */
    private static void assertEquivalent(Message actual, Message expected) {
        if (expected instanceof Execute e) {
            Execute a = (Execute) actual;
            assertThat(a.statementId()).isEqualTo(e.statementId());
            assertThat(a.sql()).isEqualTo(e.sql());
            assertThat(a.kind()).isEqualTo(e.kind());
            assertThat(a.options()).isEqualTo(e.options());
            assertThat(a.outParams()).isEqualTo(e.outParams());
            assertCells(a.params(), e.params());
        } else if (expected instanceof ExecuteBatch e) {
            ExecuteBatch a = (ExecuteBatch) actual;
            assertThat(a.statementId()).isEqualTo(e.statementId());
            assertThat(a.sql()).isEqualTo(e.sql());
            assertThat(a.kind()).isEqualTo(e.kind());
            assertThat(a.sqls()).isEqualTo(e.sqls());
            assertThat(a.paramSets()).hasSameSizeAs(e.paramSets());
            for (int i = 0; i < e.paramSets().size(); i++) {
                assertCells(a.paramSets().get(i), e.paramSets().get(i));
            }
        } else {
            assertThat(actual).isEqualTo(expected);
        }
    }

    private static void assertCells(List<Object> actual, List<Object> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            Object e = expected.get(i);
            Object a = actual.get(i);
            if (e instanceof byte[] eb) {
                assertThat((byte[]) a).isEqualTo(eb);
            } else {
                assertThat(a).isEqualTo(e);
                if (e != null) {
                    assertThat(a).isExactlyInstanceOf(e.getClass());
                }
            }
        }
    }
}
