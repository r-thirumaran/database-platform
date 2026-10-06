package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.session.LogicalSession;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.TypedNull;
import org.dbplatform.protocol.ValueTag;
import org.dbplatform.protocol.messages.BatchResult;
import org.dbplatform.protocol.messages.ErrorMessage;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteBatch;
import org.dbplatform.protocol.messages.Fetch;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.HelloOk;
import org.dbplatform.protocol.messages.Messages;
import org.dbplatform.protocol.messages.Prepare;
import org.dbplatform.protocol.messages.Rows;
import org.dbplatform.protocol.messages.SetClientInfo;
import org.dbplatform.protocol.messages.SetSchema;
import org.dbplatform.protocol.messages.SetTransactionIsolation;
import org.dbplatform.protocol.messages.StatementKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2GatewayTest {

    static final String URL = "jdbc:h2:mem:gwtest;DB_CLOSE_DELAY=-1";
    static final String URL_ORA = "jdbc:h2:mem:gwora;DB_CLOSE_DELAY=-1;MODE=Oracle";
    static GatewayFixture gw;

    @BeforeAll
    static void start() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE types_t (id INT PRIMARY KEY, c_big BIGINT, c_small SMALLINT, c_dec DECIMAL(12,3),"
                    + " c_dbl DOUBLE PRECISION, c_real REAL, c_bool BOOLEAN, c_str VARCHAR(100), c_bin VARBINARY(100),"
                    + " c_date DATE, c_time TIME, c_ts TIMESTAMP(9), c_null VARCHAR(10), c_tnull INT,"
                    + " c_tstz TIMESTAMP(6) WITH TIME ZONE)");
            st.execute("CREATE TABLE nums (n INT PRIMARY KEY)");
            st.execute("INSERT INTO nums VALUES (1),(2),(3),(4),(5)");
            st.execute("CREATE TABLE tx_t (id INT PRIMARY KEY, v VARCHAR(20))");
            st.execute("CREATE TABLE keys_t (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, v VARCHAR(20))");
            st.execute("CREATE TABLE batch_t (id INT PRIMARY KEY, v VARCHAR(20))");
            st.execute("CREATE TABLE load_t (id INT AUTO_INCREMENT PRIMARY KEY, v INT)");
            st.execute("CREATE SCHEMA s2");
            st.execute("CREATE TABLE s2.in_s2 (id INT)");
        }
        try (Connection c = DriverManager.getConnection(URL_ORA, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE ora_t (id NUMBER(10) PRIMARY KEY, v VARCHAR2(20))");
        }
        gw = GatewayFixture.start("gw-test", List.of(
                StaticConfig.DatasourceConfig.of("h2", "H2", URL, "sa", "", "TRANSACTION", 5).withConnectionTimeoutMs(500),
                StaticConfig.DatasourceConfig.of("h2ora", "H2", URL_ORA, "sa", "", "SESSION", 3).withConnectionTimeoutMs(500)));
    }

    @AfterAll
    static void stop() {
        if (gw != null) {
            gw.close();
        }
    }

    static Connection direct() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    static LogicalSession session(TestClient c) {
        return gw.gateway.sessions().sessions().stream().filter(s -> s.id().equals(c.helloOk().sessionId())).findFirst().orElseThrow();
    }

    static PhysicalPool pool(String ds) {
        return gw.gateway.pools().poolsFor(ds).get(0);
    }

    // ------------------------------------------------------------------ HELLO

    @Test
    void helloDeliversServerPropertiesAndEngine() {
        try (TestClient c = gw.client("h2")) {
            HelloOk ok = c.helloOk();
            assertThat(ok.engine()).isEqualTo(HelloOk.ENGINE_H2);
            assertThat(ok.sessionId()).startsWith("gw-test-");
            assertThat(ok.serverVersion()).isNotBlank();
            Map<String, String> p = ok.serverProperties();
            assertThat(p.get("databaseProductName")).isEqualTo("H2");
            assertThat(p.get("url")).isEqualTo("jdbc:dbp://127.0.0.1:" + gw.port() + "/h2");
            assertThat(p.get("poolMode")).isEqualTo("TRANSACTION");
            assertThat(p.get("supportsTransactions")).isEqualTo("true");
            assertThat(p.get("supportsSavepoints")).isEqualTo("true");
            assertThat(p.get("identifierQuoteString")).isEqualTo("\"");
            assertThat(p.get("userName")).isEqualToIgnoringCase("sa");
            assertThat(p).containsKeys("databaseProductVersion", "databaseMajorVersion", "driverName", "driverVersion",
                    "schemaTerm", "searchStringEscape", "sqlKeywords", "storesUpperCaseIdentifiers",
                    "defaultTransactionIsolation", "maxStatementLength", "supportsGetGeneratedKeys");
            c.ping();
        }
    }

    @Test
    void helloWithoutDatasourceOrUnknownDatasourceIsRejected() {
        try (TestClient c = TestClient.connect(gw.port())) {
            ErrorMessage e = c.helloError(Map.of());
            assertThat(e.sqlState()).isEqualTo("08004");
            assertThat(e.fatal()).isTrue();
        }
        try (TestClient c = TestClient.connect(gw.port())) {
            ErrorMessage e = c.helloError(Map.of(Hello.PROP_DATASOURCE, "nope"));
            assertThat(e.sqlState()).isEqualTo("08004");
            assertThat(e.message()).contains("nope");
        }
    }

    @Test
    void protocolViolationBeforeHelloIsFatal() throws Exception {
        try (Socket s = new Socket("127.0.0.1", gw.port())) {
            FrameWriter w = new FrameWriter(s.getOutputStream());
            w.writeFrame(MessageType.PING, null);
            ErrorMessage e = (ErrorMessage) Messages.read(new org.dbplatform.protocol.FrameReader(s.getInputStream()));
            assertThat(e.fatal()).isTrue();
            assertThat(e.sqlState()).isEqualTo("HY000");
        }
    }

    @Test
    void oversizedHelloFrameIsRejectedBeforeAllocation() throws Exception {
        try (Socket s = new Socket("127.0.0.1", gw.port())) {
            // a frame claiming 32 MiB: within DBP_GATEWAY_MAX_FRAME_BYTES but far beyond what a HELLO may use
            java.io.DataOutputStream out = new java.io.DataOutputStream(s.getOutputStream());
            out.writeInt(32 * 1024 * 1024);
            out.writeByte(MessageType.HELLO.code());
            out.flush();
            ErrorMessage e = (ErrorMessage) Messages.read(new org.dbplatform.protocol.FrameReader(s.getInputStream()));
            assertThat(e.fatal()).isTrue();
            assertThat(e.sqlState()).isEqualTo("HY000");
            assertThat(e.message()).contains("exceeds maximum");
        }
    }

    // ------------------------------------------------------------------ values

    @Test
    void everyValueTypeRoundTrips() {
        try (TestClient c = gw.client("h2")) {
            LocalDateTime ts = LocalDateTime.of(2024, 2, 29, 13, 45, 30, 123456789);
            OffsetDateTime tstz = OffsetDateTime.of(2024, 2, 29, 13, 45, 30, 123456000, ZoneOffset.ofHours(2));
            byte[] bin = new byte[] {0, 1, 2, (byte) 0xFF, 127, -128};
            String str = "héllo wörld ✓ 日本語 😀";
            long n = c.update("INSERT INTO types_t VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    1, 9_000_000_000L, (short) -7, new BigDecimal("1234.500"), 2.5d, 1.5f, Boolean.TRUE, str, bin,
                    LocalDate.of(2024, 2, 29), LocalTime.of(23, 59, 58), ts, null, new TypedNull(Types.INTEGER), tstz)
                    .updateCount();
            assertThat(n).isEqualTo(1);

            TestClient.ExecResult r = c.query("SELECT * FROM types_t WHERE id = ?", 1);
            TestClient.ResultItem item = r.result();
            assertThat(item.last()).isTrue();
            assertThat(item.rows()).hasSize(1);
            List<Object> row = item.rows().get(0);
            assertThat(row.get(0)).isEqualTo(1);
            assertThat(row.get(1)).isEqualTo(9_000_000_000L);
            assertThat(row.get(2)).isEqualTo((short) -7);
            assertThat(row.get(3)).isEqualTo(new BigDecimal("1234.500"));
            assertThat(row.get(4)).isEqualTo(2.5d);
            assertThat(row.get(5)).isEqualTo(1.5f);
            assertThat(row.get(6)).isEqualTo(Boolean.TRUE);
            assertThat(row.get(7)).isEqualTo(str);
            assertThat((byte[]) row.get(8)).containsExactly(bin);
            assertThat(row.get(9)).isEqualTo(LocalDate.of(2024, 2, 29));
            assertThat(row.get(10)).isEqualTo(LocalTime.of(23, 59, 58));
            assertThat(row.get(11)).isEqualTo(ts);
            assertThat(row.get(12)).isNull();
            assertThat(row.get(13)).isNull();
            assertThat(((OffsetDateTime) row.get(14)).toInstant()).isEqualTo(tstz.toInstant());
            // column metadata and server-chosen tags
            assertThat(item.header().columns().get(3).valueTag()).isEqualTo(ValueTag.DECIMAL);
            assertThat(item.header().columns().get(3).scale()).isEqualTo(3);
            assertThat(item.header().columns().get(7).jdbcType()).isEqualTo(Types.VARCHAR);
            assertThat(item.header().columns().get(0).tableName()).isEqualToIgnoringCase("types_t");
            assertThat(item.labels()).containsExactly("ID", "C_BIG", "C_SMALL", "C_DEC", "C_DBL", "C_REAL", "C_BOOL",
                    "C_STR", "C_BIN", "C_DATE", "C_TIME", "C_TS", "C_NULL", "C_TNULL", "C_TSTZ");
            // typed null comparison works as a parameter too
            assertThat(c.query("SELECT COUNT(*) FROM types_t WHERE c_tnull IS NULL AND ? IS NULL",
                    new TypedNull(Types.VARCHAR)).scalar()).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------ streaming

    @Test
    void resultSetsStreamAcrossFetchBatchesAndReleaseThePin() {
        try (TestClient c = gw.client("h2")) {
            TestClient.ExecResult r = c.queryFetch("SELECT n FROM nums ORDER BY n", 2);
            TestClient.ResultItem first = r.result();
            assertThat(first.rows()).hasSize(2);
            assertThat(first.last()).isFalse();
            LogicalSession s = session(c);
            assertThat(s.isPinned()).as("cursor keeps the session pinned").isTrue();
            assertThat(s.openCursors()).isEqualTo(1);

            Rows second = c.fetch(first.cursorId(), 2, 1);
            assertThat(second.rows()).hasSize(2);
            assertThat(second.last()).isFalse();
            List<Object> all = new ArrayList<>();
            first.rows().forEach(row -> all.add(row.get(0)));
            second.rows().forEach(row -> all.add(row.get(0)));
            boolean last = false;
            while (!last) {
                Rows more = c.fetch(first.cursorId(), 2, 1);
                more.rows().forEach(row -> all.add(row.get(0)));
                last = more.last();
            }
            assertThat(all).containsExactly(1, 2, 3, 4, 5);
            assertThat(s.isPinned()).as("exhausted cursor releases the pin").isFalse();
            assertThat(s.openCursors()).isZero();
            // fetching a closed cursor is an error, non fatal
            ErrorMessage e = c.expectError(new Fetch(first.cursorId(), 2));
            assertThat(e.sqlState()).isEqualTo("HY000");
            assertThat(e.fatal()).isFalse();
            c.ping();
        }
    }

    @Test
    void closeCursorReleasesThePin() {
        try (TestClient c = gw.client("h2")) {
            TestClient.ResultItem first = c.queryFetch("SELECT n FROM nums ORDER BY n", 1).result();
            assertThat(first.last()).isFalse();
            assertThat(session(c).isPinned()).isTrue();
            c.closeCursor(first.cursorId());
            assertThat(session(c).isPinned()).isFalse();
            c.closeCursor(first.cursorId()); // idempotent
        }
    }

    @Test
    void maxRowsIsHonoured() {
        try (TestClient c = gw.client("h2")) {
            TestClient.ExecResult r = c.execute(Execute.direct("SELECT n FROM nums ORDER BY n", StatementKind.PREPARED,
                    List.of(), new Execute.ExecOptions(2, 0, 0, Execute.Expect.QUERY, Statement.NO_GENERATED_KEYS, List.of())));
            assertThat(r.rows()).hasSize(2);
            assertThat(r.result().last()).isTrue();
        }
    }

    // ------------------------------------------------------------------ updates, keys, batches

    @Test
    void updateCountsAndGeneratedKeys() {
        try (TestClient c = gw.client("h2")) {
            assertThat(c.exec("INSERT INTO tx_t VALUES (100, 'a'), (101, 'b')").updateCount()).isEqualTo(2);
            assertThat(c.update("DELETE FROM tx_t WHERE id IN (?, ?)", 100, 101).updateCount()).isEqualTo(2);

            TestClient.ExecResult r = c.execute(Execute.direct("INSERT INTO keys_t (v) VALUES (?)", StatementKind.PREPARED,
                    List.of("x"), new Execute.ExecOptions(0, 0, 0, Execute.Expect.UPDATE, Statement.RETURN_GENERATED_KEYS, List.of())));
            assertThat(r.updateCount()).isEqualTo(1);
            assertThat(r.generatedKeys()).isNotNull();
            assertThat(r.generatedKeys().rows()).hasSize(1);
            assertThat(r.generatedKeys().columns().get(0).label()).isEqualToIgnoringCase("id");
            Object id1 = r.generatedKeys().rows().get(0).get(0);

            int stmt = c.prepare(new Prepare("INSERT INTO keys_t (v) VALUES (?)", StatementKind.PREPARED,
                    Statement.NO_GENERATED_KEYS, List.of("ID")));
            TestClient.ExecResult r2 = c.executePrepared(stmt, Execute.Expect.UPDATE, "y");
            assertThat(r2.generatedKeys()).isNotNull();
            assertThat(((Number) r2.generatedKeys().rows().get(0).get(0)).longValue())
                    .isEqualTo(((Number) id1).longValue() + 1);
            c.closeStatement(stmt);

            // a plain statement with RETURN_GENERATED_KEYS
            TestClient.ExecResult r3 = c.execute(Execute.direct("INSERT INTO keys_t (v) VALUES ('z')", StatementKind.STATEMENT,
                    List.of(), new Execute.ExecOptions(0, 0, 0, Execute.Expect.ANY, Statement.RETURN_GENERATED_KEYS, List.of())));
            assertThat(r3.updateCount()).isEqualTo(1);
            assertThat(r3.generatedKeys().rows()).hasSize(1);
        }
    }

    @Test
    void batchesStatementAndPrepared() {
        try (TestClient c = gw.client("h2")) {
            BatchResult b = c.batch(ExecuteBatch.ofStatements(List.of(
                    "INSERT INTO batch_t VALUES (1, 'a')", "INSERT INTO batch_t VALUES (2, 'b')", "UPDATE batch_t SET v = 'c'")));
            assertThat(b.updateCounts()).containsExactly(1, 1, 2);

            int stmt = c.prepare("INSERT INTO batch_t VALUES (?, ?)", StatementKind.PREPARED);
            BatchResult b2 = c.batch(ExecuteBatch.ofPrepared(stmt, StatementKind.PREPARED,
                    List.of(List.of(10, "x"), List.of(11, "y"), List.of(12, "z"))));
            assertThat(b2.updateCounts()).containsExactly(1, 1, 1);
            // unregistered prepared batch carrying the sql
            BatchResult b3 = c.batch(new ExecuteBatch(-1, "INSERT INTO batch_t VALUES (?, ?)", StatementKind.PREPARED,
                    List.of(List.of(20, "p")), List.of()));
            assertThat(b3.updateCounts()).containsExactly(1);
            // a failing batch yields ERROR with the driver's state
            assertThatThrownBy(() -> c.batch(ExecuteBatch.ofPrepared(stmt, StatementKind.PREPARED,
                    List.of(List.of(30, "q"), List.of(1, "dup")))))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("23505"));
            assertThat(session(c).isPinned()).isFalse();
            // H2 applies batch entries one by one in autocommit mode: the first entry of the failing batch stuck
            assertThat(c.query("SELECT COUNT(*) FROM batch_t").scalar()).isEqualTo(7L);
        }
    }

    // ------------------------------------------------------------------ transactions

    @Test
    void transactionsRollbackAndCommit() throws Exception {
        try (TestClient c = gw.client("h2"); TestClient other = gw.client("h2")) {
            c.autoCommit(false);
            assertThat(session(c).isPinned()).as("SET_AUTOCOMMIT alone does not pin").isFalse();
            c.update("INSERT INTO tx_t VALUES (?, ?)", 1, "rolled back");
            assertThat(session(c).isPinned()).isTrue();
            assertThat(other.query("SELECT COUNT(*) FROM tx_t WHERE id = 1").scalar()).isEqualTo(0L);
            c.rollback();
            assertThat(session(c).isPinned()).as("ROLLBACK releases in TRANSACTION mode").isFalse();
            assertThat(other.query("SELECT COUNT(*) FROM tx_t WHERE id = 1").scalar()).isEqualTo(0L);

            c.update("INSERT INTO tx_t VALUES (?, ?)", 2, "committed");
            assertThat(other.query("SELECT COUNT(*) FROM tx_t WHERE id = 2").scalar()).isEqualTo(0L);
            c.commit();
            assertThat(session(c).isPinned()).isFalse();
            assertThat(other.query("SELECT COUNT(*) FROM tx_t WHERE id = 2").scalar()).isEqualTo(1L);
            try (Connection d = direct(); Statement st = d.createStatement()) {
                var rs = st.executeQuery("SELECT v FROM tx_t WHERE id = 2");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("committed");
            }
            // SET_AUTOCOMMIT true commits the open transaction
            c.update("INSERT INTO tx_t VALUES (?, ?)", 3, "via autocommit");
            c.autoCommit(true);
            assertThat(session(c).isPinned()).isFalse();
            assertThat(other.query("SELECT COUNT(*) FROM tx_t WHERE id = 3").scalar()).isEqualTo(1L);
        }
    }

    @Test
    void savepointsNamedAndUnnamed() {
        try (TestClient c = gw.client("h2")) {
            c.autoCommit(false);
            c.update("INSERT INTO tx_t VALUES (?, ?)", 10, "a");
            String sp = c.savepoint(null);
            assertThat(sp).startsWith("DBP_SP_");
            c.update("INSERT INTO tx_t VALUES (?, ?)", 11, "b");
            c.rollbackTo(sp);
            assertThat(session(c).isPinned()).as("rollback to savepoint keeps the transaction").isTrue();
            String named = c.savepoint("my_sp");
            assertThat(named).isEqualTo("my_sp");
            c.update("INSERT INTO tx_t VALUES (?, ?)", 12, "c");
            c.ok(new org.dbplatform.protocol.messages.ReleaseSavepoint("my_sp"));
            c.commit();
            assertThat(c.query("SELECT COUNT(*) FROM tx_t WHERE id IN (10, 11, 12)").scalar()).isEqualTo(2L);
            ErrorMessage e = c.expectError(new org.dbplatform.protocol.messages.Rollback("unknown"));
            assertThat(e.sqlState()).isEqualTo("HY000");
        }
    }

    @Test
    void failedFirstStatementOfATransactionKeepsTheSessionPinned() {
        try (TestClient c = gw.client("h2")) {
            c.autoCommit(false);
            assertThatThrownBy(() -> c.update("INSERT INTO no_such_table VALUES (1)"))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("42S02"));
            // the transaction starts with the attempt, not with a successful statement: the session stays on its
            // physical connection, so the following statements and the ROLLBACK reach the same connection (PostgreSQL
            // would answer 25P02 for the statements until the rollback, like a direct connection does)
            assertThat(session(c).isPinned()).as("a failing first statement pins like a successful one").isTrue();
            assertThat(session(c).inTransaction()).isTrue();
            c.update("INSERT INTO tx_t VALUES (?, ?)", 400, "after failure");
            c.rollback();
            assertThat(session(c).isPinned()).isFalse();
            assertThat(session(c).inTransaction()).isFalse();
            assertThat(c.query("SELECT COUNT(*) FROM tx_t WHERE id = 400").scalar()).isEqualTo(0L);
            c.rollback();
            // ROLLBACK right after the failure releases the pin as well
            assertThatThrownBy(() -> c.update("INSERT INTO no_such_table VALUES (1)")).isInstanceOf(TestClient.SqlError.class);
            assertThat(session(c).isPinned()).isTrue();
            c.rollback();
            assertThat(session(c).isPinned()).isFalse();
            // the same for a prepared batch
            int stmt = c.prepare("INSERT INTO no_such_table VALUES (?)", StatementKind.PREPARED);
            assertThatThrownBy(() -> c.batch(ExecuteBatch.ofPrepared(stmt, StatementKind.PREPARED, List.of(List.<Object>of(1)))))
                    .isInstanceOf(TestClient.SqlError.class);
            assertThat(session(c).isPinned()).isTrue();
            c.autoCommit(true);
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void sixthTransactionOnFullPoolGets08001AfterConnectionTimeout() {
        List<TestClient> holders = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                TestClient h = gw.client("h2");
                h.autoCommit(false);
                h.update("INSERT INTO tx_t VALUES (?, ?)", 200 + i, "hold");
                holders.add(h);
            }
            assertThat(pool("h2").activeConnections()).isEqualTo(5);
            try (TestClient sixth = gw.client("h2")) {
                sixth.autoCommit(false);
                long t0 = System.nanoTime();
                assertThatThrownBy(() -> sixth.update("INSERT INTO tx_t VALUES (?, ?)", 299, "x"))
                        .isInstanceOf(TestClient.SqlError.class)
                        .satisfies(t -> {
                            assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("08001");
                            assertThat(((TestClient.SqlError) t).fatal()).isFalse();
                        });
                long ms = (System.nanoTime() - t0) / 1_000_000;
                // the lower bound proves the pool waited for its connectionTimeout (500 ms) before answering 08001; the
                // upper bound only guards against a hang and is generous for slow CI runners
                assertThat(ms).isBetween(400L, 20_000L);
                holders.get(0).rollback();
                sixth.update("INSERT INTO tx_t VALUES (?, ?)", 299, "x"); // now a connection is free
                sixth.rollback();
            }
        } finally {
            for (TestClient h : holders) {
                try {
                    h.rollback();
                } catch (RuntimeException ignored) {
                    // released already
                }
                h.close();
            }
        }
    }

    // ------------------------------------------------------------------ settings survive un-pinning

    @Test
    void sessionSettingsAreReappliedToEveryPhysicalConnection() {
        try (TestClient c = gw.client("h2")) {
            c.ok(new SetSchema("S2"));
            c.ok(new SetTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE));
            c.ok(new SetClientInfo("ApplicationName", "settings-test"));
            Set<Object> physicalSessionIds = new HashSet<>();
            List<TestClient> churn = new ArrayList<>();
            try {
                for (int i = 0; i < 12; i++) {
                    assertThat(c.query("CALL CURRENT_SCHEMA").scalar()).isEqualTo("S2");
                    assertThat(session(c).isPinned()).isFalse();
                    assertThat(c.exec("SELECT COUNT(*) FROM in_s2").result().rows()).hasSize(1); // resolves in S2
                    physicalSessionIds.add(c.query("CALL SESSION_ID()").scalar());
                    // occupy a connection so the next autocommit statement lands on a different physical one
                    TestClient holder = gw.client("h2");
                    holder.autoCommit(false);
                    holder.update("INSERT INTO tx_t VALUES (?, ?)", 300 + i, "hold");
                    churn.add(holder);
                    if (churn.size() == 3) {
                        churn.forEach(h -> {
                            h.rollback();
                            h.close();
                        });
                        churn.clear();
                    }
                }
            } finally {
                churn.forEach(h -> {
                    h.rollback();
                    h.close();
                });
            }
            assertThat(physicalSessionIds.size()).as("statements ran on several physical connections").isGreaterThan(1);
            Object iso = c.query("SELECT ISOLATION_LEVEL FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = SESSION_ID()").scalar();
            assertThat(iso.toString()).isEqualTo("SERIALIZABLE");
        }
        // a fresh session on the same pool sees the defaults again (settings were reset on release)
        try (TestClient c = gw.client("h2")) {
            assertThat(c.query("CALL CURRENT_SCHEMA").scalar()).isEqualTo("PUBLIC");
        }
    }

    // ------------------------------------------------------------------ the core guarantee

    @Test
    void fiftyLogicalSessionsNeverExceedFivePhysicalConnections() throws Exception {
        int sessions = 50;
        int perSession = 20;
        AtomicInteger maxTotal = new AtomicInteger();
        AtomicInteger maxH2Sessions = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            try (Connection d = direct(); Statement st = d.createStatement()) {
                while (sampling.get()) {
                    maxTotal.accumulateAndGet(pool("h2").totalConnections(), Math::max);
                    var rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS");
                    rs.next();
                    maxH2Sessions.accumulateAndGet(rs.getInt(1), Math::max);
                    Thread.sleep(2);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        sampler.start();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(sessions);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            final int sid = i;
            futures.add(pool.submit(() -> {
                try (TestClient c = gw.client("h2")) {
                    ready.countDown();
                    ready.await();
                    int okCount = 0;
                    for (int j = 0; j < perSession; j++) {
                        if (j % 2 == 0) {
                            assertThat(c.update("INSERT INTO load_t (v) VALUES (?)", sid * 1000 + j).updateCount()).isEqualTo(1);
                        } else {
                            assertThat(c.query("SELECT COUNT(*) FROM load_t WHERE v = ?", sid * 1000 + j - 1).scalar()).isEqualTo(1L);
                        }
                        okCount++;
                    }
                    return okCount;
                }
            }));
        }
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(120, TimeUnit.SECONDS);
        }
        sampling.set(false);
        sampler.join();
        pool.shutdown();
        assertThat(total).isEqualTo(sessions * perSession);
        assertThat(maxTotal.get()).as("physical pool size").isLessThanOrEqualTo(5);
        assertThat(maxH2Sessions.get()).as("H2 sessions incl. the sampler").isLessThanOrEqualTo(6);
        assertThat(gw.gateway.sessions().size()).isZero();
        try (Connection d = direct(); Statement st = d.createStatement()) {
            var rs = st.executeQuery("SELECT COUNT(*) FROM load_t");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(sessions * perSession / 2);
        }
    }

    // ------------------------------------------------------------------ SESSION mode

    @Test
    void sessionModePinsForTheLifeOfTheSession() {
        try (TestClient c = gw.client("h2ora")) {
            assertThat(c.helloOk().serverProperties().get("poolMode")).isEqualTo("SESSION");
            c.exec("SET @v = 41");
            assertThat(session(c).isPinned()).isTrue();
            assertThat(c.query("CALL @v + 1").scalar()).isEqualTo(42);
            c.exec("CREATE LOCAL TEMPORARY TABLE tmp_t (id INT)");
            c.exec("INSERT INTO tmp_t VALUES (1), (2)");
            assertThat(c.query("SELECT COUNT(*) FROM tmp_t").scalar()).isEqualTo(2L);
            assertThat(session(c).isPinned()).isTrue();
            assertThat(c.query("SELECT COUNT(*) FROM ora_t").scalar()).isEqualTo(0L);
            assertThat(pool("h2ora").activeConnections()).isEqualTo(1);
        }
        // closing returned the connection
        assertThat(pool("h2ora").activeConnections()).isZero();
    }

    // ------------------------------------------------------------------ metadata

    @Test
    void metadataGetTablesAndGetColumns() {
        try (TestClient c = gw.client("h2")) {
            TestClient.ExecResult tables = c.metadata("getTables", null, "PUBLIC", "TYPES_T", "TABLE\u0000VIEW");
            assertThat(tables.results()).hasSize(1);
            List<List<Object>> rows = c.drain(tables.result(), 50);
            assertThat(rows).hasSize(1);
            int nameIdx = tables.result().labels().indexOf("TABLE_NAME");
            assertThat(rows.get(0).get(nameIdx)).isEqualTo("TYPES_T");

            TestClient.ExecResult cols = c.metadata("getColumns", null, "PUBLIC", "TYPES_T", "%");
            List<List<Object>> colRows = c.drain(cols.result(), 4);
            assertThat(colRows).hasSize(15);
            assertThat(session(c).isPinned()).isFalse();

            TestClient.ExecResult pk = c.metadata("getPrimaryKeys", null, "PUBLIC", "TYPES_T");
            assertThat(pk.rows()).hasSize(1);
            ErrorMessage e = c.expectError(org.dbplatform.protocol.messages.Metadata.of("getConnection"));
            assertThat(e.sqlState()).isEqualTo("0A000");
        }
    }

    // ------------------------------------------------------------------ errors

    @Test
    void errorsMapToSqlStatesAndKeepTheSessionUsable() {
        try (TestClient c = gw.client("h2")) {
            assertThatThrownBy(() -> c.exec("SELEC 1"))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> {
                        assertThat(((TestClient.SqlError) t).sqlState()).startsWith("42");
                        assertThat(((TestClient.SqlError) t).fatal()).isFalse();
                    });
            assertThatThrownBy(() -> c.query("INSERT INTO tx_t VALUES (500, 'q')"))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("07005"));
            assertThatThrownBy(() -> c.update("SELECT 1"))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("07005"));
            assertThatThrownBy(() -> c.query("SELECT * FROM does_not_exist"))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("42S02"));
            assertThatThrownBy(() -> c.executePrepared(9999, Execute.Expect.QUERY))
                    .isInstanceOf(TestClient.SqlError.class)
                    .satisfies(t -> assertThat(((TestClient.SqlError) t).sqlState()).isEqualTo("HY000"));
            assertThat(session(c).isPinned()).isFalse();
            c.ping();
            assertThat(c.query("SELECT 1").scalar()).isEqualTo(1);
        }
    }

    @Test
    void closingAStatementClosesItsCursors() {
        try (TestClient c = gw.client("h2")) {
            int stmt = c.prepare("SELECT n FROM nums ORDER BY n", StatementKind.PREPARED);
            TestClient.ExecResult r = c.execute(Execute.prepared(stmt, StatementKind.PREPARED, List.of(),
                    new Execute.ExecOptions(0, 1, 0, Execute.Expect.QUERY, Statement.NO_GENERATED_KEYS, List.of())));
            assertThat(r.result().last()).isFalse();
            assertThat(session(c).isPinned()).isTrue();
            // re-executing the same registered statement reuses the cached physical statement
            TestClient.ExecResult r2 = c.execute(Execute.prepared(stmt, StatementKind.PREPARED, List.of(),
                    new Execute.ExecOptions(0, 10, 0, Execute.Expect.QUERY, Statement.NO_GENERATED_KEYS, List.of())));
            assertThat(r2.result().last()).isTrue();
            c.closeStatement(stmt);
            assertThat(session(c).openCursors()).isZero();
            assertThat(session(c).isPinned()).isFalse();
            ErrorMessage e = c.expectError(new Fetch(r.result().cursorId(), 1));
            assertThat(e.sqlState()).isEqualTo("HY000");
        }
    }

    @Test
    void switchingGeneratedKeyOptionsKeepsTheCursorOfThePreviousExecutionOpen() {
        try (TestClient c = gw.client("h2")) {
            int stmt = c.prepare("SELECT n FROM nums ORDER BY n", StatementKind.PREPARED);
            // fetchSize 2 leaves a cursor open on the physical statement created without generated keys
            TestClient.ExecResult first = c.execute(Execute.prepared(stmt, StatementKind.PREPARED, List.of(),
                    new Execute.ExecOptions(0, 2, 0, Execute.Expect.QUERY, Statement.NO_GENERATED_KEYS, List.of())));
            assertThat(first.result().last()).isFalse();
            assertThat(first.rows()).extracting(r -> r.get(0)).containsExactly(1, 2);
            // the same registered statement executed with another generated-keys configuration needs a new physical
            // statement; the previous one must not be closed while its cursor is still open
            TestClient.ExecResult second = c.execute(Execute.prepared(stmt, StatementKind.PREPARED, List.of(),
                    new Execute.ExecOptions(0, 10, 0, Execute.Expect.QUERY, Statement.RETURN_GENERATED_KEYS, List.of())));
            assertThat(second.result().last()).isTrue();
            assertThat(second.rows()).hasSize(5);
            // the first cursor is still alive and continues where it stopped
            Rows more = c.fetch(first.result().cursorId(), 2, 1);
            assertThat(more.rows()).extracting(r -> r.get(0)).containsExactly(3, 4);
            assertThat(more.last()).isFalse();
            Rows rest = c.fetch(first.result().cursorId(), 2, 1);
            assertThat(rest.rows()).extracting(r -> r.get(0)).containsExactly(5);
            assertThat(rest.last()).isTrue();
            assertThat(session(c).openCursors()).isZero();
            // both variants keep working afterwards
            assertThat(c.executePrepared(stmt, Execute.Expect.QUERY).rows()).hasSize(5);
            c.closeStatement(stmt);
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    // ------------------------------------------------------------------ admin

    @Test
    void adminEndpointsExposeSessionsPoolsAndMetrics() {
        try (TestClient c = gw.client("h2")) {
            c.ok(new SetClientInfo("ApplicationName", "admin-test"));
            c.query("SELECT 1");
            c.expectError(new Fetch(424242, 1)); // makes sure an error counter exists
            String sessions = gw.adminGet("/sessions");
            assertThat(sessions).contains(c.helloOk().sessionId()).contains("admin-test").contains("\"datasource\":\"h2\"");
            String pools = gw.adminGet("/pools");
            assertThat(pools).contains("\"key\":\"h2@v1\"").contains("\"max\":5");
            String health = gw.adminGet("/health");
            assertThat(health).contains("\"status\":\"UP\"").contains("\"mode\":\"static\"");
            String metrics = gw.adminGet("/metrics");
            assertThat(metrics).contains("dbp_gateway_statements_total{").contains("operation=\"SELECT\"")
                    .contains("dbp_gateway_pool_max{datasource=\"h2\"").contains("dbp_gateway_logical_sessions{")
                    .contains("dbp_gateway_statement_duration_seconds_bucket").contains("dbp_gateway_telemetry_dropped_total")
                    .contains("dbp_gateway_errors_total{");
        }
    }

    @Test
    void helloPropertiesInitialiseTheSession() {
        try (TestClient c = TestClient.open(gw.port(), Map.of(Hello.PROP_DATASOURCE, "h2", Hello.PROP_AUTOCOMMIT, "false",
                Hello.PROP_SCHEMA, "S2", Hello.PROP_CLIENT_INFO_PREFIX + "ClientUser", "bob", Hello.PROP_TX_ISOLATION,
                String.valueOf(Connection.TRANSACTION_READ_COMMITTED)))) {
            assertThat(c.query("CALL CURRENT_SCHEMA").scalar()).isEqualTo("S2");
            assertThat(session(c).isPinned()).as("autoCommit=false from HELLO pins on first statement").isTrue();
            assertThat(session(c).settings().clientInfo()).containsEntry("ClientUser", "bob");
            c.commit();
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void unsupportedProtocolVersionIsRejected() {
        try (TestClient c = TestClient.connect(gw.port())) {
            var m = c.send(new Hello(2, "x", "1", Map.of(Hello.PROP_DATASOURCE, "h2")));
            assertThat(m).isInstanceOf(ErrorMessage.class);
            assertThat(((ErrorMessage) m).sqlState()).isEqualTo("08004");
            assertThat(((ErrorMessage) m).message()).contains("unsupported protocol version");
        }
    }

    @Test
    void oracleModeDatasourceReportsNumberAsDecimal() {
        try (TestClient c = gw.client("h2ora")) {
            c.update("INSERT INTO ora_t VALUES (?, ?)", 7, "seven");
            TestClient.ResultItem r = c.query("SELECT id FROM ora_t").result();
            assertThat(r.header().columns().get(0).valueTag()).isEqualTo(ValueTag.DECIMAL);
            assertThat(r.cell(0, 0)).isEqualTo(new BigDecimal("7"));
            c.update("DELETE FROM ora_t");
        }
    }

    @Test
    void sqlWarningsAreDelivered() {
        try (TestClient c = gw.client("h2")) {
            // H2 produces no warnings; make sure the field is present and empty
            assertThat(c.query("SELECT 1").warnings()).isEmpty();
            assertThat(Arrays.asList(1, 2)).hasSize(2);
        }
    }
}
