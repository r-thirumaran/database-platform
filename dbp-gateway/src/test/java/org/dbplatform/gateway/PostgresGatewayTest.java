package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.session.LogicalSession;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.SetClientInfo;
import org.dbplatform.protocol.messages.StatementKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresGatewayTest {

    static final String DB = "dbptest";
    static EmbeddedPg pg;
    static GatewayFixture gw;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPg.start();
        try (Connection c = pg.superuser("postgres"); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        try (Connection c = pg.superuser(DB); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id serial PRIMARY KEY, name text NOT NULL)");
            st.execute("INSERT INTO orders (name) VALUES ('a'), ('b'), ('c'), ('d'), ('e')");
            st.execute("CREATE FUNCTION add_one(INOUT x integer, OUT y text) AS $$ BEGIN x := x + 1; y := 'v' || x; END $$ LANGUAGE plpgsql");
            st.execute("CREATE FUNCTION open_orders() RETURNS refcursor AS $$ DECLARE c refcursor;"
                    + " BEGIN OPEN c FOR SELECT id, name FROM orders ORDER BY id; RETURN c; END $$ LANGUAGE plpgsql");
            st.execute("CREATE PROCEDURE bump(INOUT x integer) AS $$ BEGIN x := x * 2; END $$ LANGUAGE plpgsql");
            st.execute("CREATE TABLE load_t (id serial PRIMARY KEY, v integer)");
        }
        gw = GatewayFixture.start("gw-pg", List.of(
                StaticConfig.DatasourceConfig.of("pg", "POSTGRES", pg.jdbcUrl(DB), "postgres", "postgres", "TRANSACTION", 5)
                        .withConnectionTimeoutMs(1000)));
    }

    @AfterAll
    static void stop() throws Exception {
        if (gw != null) {
            gw.close();
        }
        if (pg != null) {
            pg.close();
        }
    }

    static LogicalSession session(TestClient c) {
        return gw.gateway.sessions().sessions().stream().filter(s -> s.id().equals(c.helloOk().sessionId())).findFirst().orElseThrow();
    }

    static PhysicalPool pool() {
        return gw.gateway.pools().poolsFor("pg").get(0);
    }

    static int backends(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = '" + DB
                     + "' AND application_name LIKE 'dbp-gateway/%'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void helloReportsPostgresEngine() {
        try (TestClient c = gw.client("pg")) {
            assertThat(c.helloOk().engine()).isEqualTo("POSTGRES");
            assertThat(c.helloOk().serverProperties().get("databaseProductName")).isEqualTo("PostgreSQL");
            assertThat(c.query("SELECT 1::int").scalar()).isEqualTo(1);
        }
    }

    @Test
    void callableWithInoutAndOutParameters() {
        try (TestClient c = gw.client("pg")) {
            TestClient.ExecResult r = c.execute(new Execute(-1, "{call add_one(?, ?)}", StatementKind.CALLABLE,
                    Arrays.asList(41, null), Execute.ExecOptions.DEFAULT,
                    List.of(Execute.OutParam.of(1, Types.INTEGER), Execute.OutParam.of(2, Types.VARCHAR))));
            assertThat(r.outParams()).isNotNull();
            assertThat(r.outParams().asMap()).containsEntry(1, 42).containsEntry(2, "v42");
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void procedureWithInoutParameterUsingCallSyntax() {
        // a real PROCEDURE needs CALL: direct SQL with expect ANY, parameters bound as a prepared statement
        try (TestClient c = gw.client("pg")) {
            TestClient.ExecResult r = c.execute(new Execute(-1, "CALL bump(?)", StatementKind.PREPARED,
                    List.of(21), Execute.ExecOptions.DEFAULT, List.of()));
            assertThat(r.results()).hasSize(1);
            assertThat(r.scalar()).isEqualTo(42);
        }
    }

    @Test
    void refCursorOutParameterIsStreamedAsResultItem() {
        try (TestClient c = gw.client("pg")) {
            c.autoCommit(false);
            TestClient.ExecResult r = c.execute(new Execute(-1, "{? = call open_orders()}", StatementKind.CALLABLE,
                    Arrays.asList((Object) null),
                    new Execute.ExecOptions(0, 2, 0, Execute.Expect.ANY, Statement.NO_GENERATED_KEYS, List.of()),
                    List.of(Execute.OutParam.of(1, Types.REF_CURSOR))));
            // section 4.7: the cursor travels as a result item before OUT_PARAMS, whose entry is the INT cursorId
            assertThat(r.results()).hasSize(1);
            TestClient.ResultItem item = r.result();
            assertThat(item.rows()).hasSize(2);
            assertThat(item.last()).isFalse();
            assertThat(item.labels()).containsExactly("id", "name");
            assertThat(r.outParams()).isNotNull();
            assertThat(r.outParams().asMap()).containsEntry(1, item.cursorId());
            assertThat(session(c).openCursors()).isEqualTo(1);
            List<List<Object>> all = c.drain(item, 2);
            assertThat(all).hasSize(5);
            assertThat(all.get(4).get(1)).isEqualTo("e");
            assertThat(session(c).openCursors()).isZero();
            assertThat(session(c).isPinned()).as("transaction still open").isTrue();
            c.commit();
            assertThat(session(c).isPinned()).isFalse();
        }
    }

    @Test
    void returningViaGeneratedKeys() {
        try (TestClient c = gw.client("pg")) {
            TestClient.ExecResult r = c.execute(Execute.direct("INSERT INTO orders (name) VALUES (?)", StatementKind.PREPARED,
                    List.of("gen"), new Execute.ExecOptions(0, 0, 0, Execute.Expect.UPDATE, Statement.RETURN_GENERATED_KEYS, List.of())));
            assertThat(r.updateCount()).isEqualTo(1);
            assertThat(r.generatedKeys()).isNotNull();
            int idIdx = r.generatedKeys().columns().stream().map(col -> col.label()).toList().indexOf("id");
            assertThat(idIdx).isGreaterThanOrEqualTo(0);
            assertThat(((Number) r.generatedKeys().rows().get(0).get(idIdx)).intValue()).isGreaterThan(5);

            TestClient.ExecResult r2 = c.execute(Execute.direct("INSERT INTO orders (name) VALUES (?)", StatementKind.PREPARED,
                    List.of("gen2"), new Execute.ExecOptions(0, 0, 0, Execute.Expect.UPDATE, Statement.NO_GENERATED_KEYS, List.of("id"))));
            assertThat(r2.generatedKeys().columns()).hasSize(1);
            assertThat(r2.generatedKeys().columns().get(0).label()).isEqualTo("id");
            c.update("DELETE FROM orders WHERE name LIKE 'gen%'");
        }
    }

    @Test
    void applicationNameIsVisibleInPgStatActivity() throws Exception {
        String sql = "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()";
        try (TestClient c = gw.client("pg")) {
            assertThat(c.query(sql).scalar()).isEqualTo("dbp-gateway/gw-pg");
            c.autoCommit(false);
            c.ok(new SetClientInfo("ApplicationName", "orders-service"));
            assertThat(c.query(sql).scalar()).isEqualTo("orders-service");
            assertThat(session(c).isPinned()).isTrue();
            c.commit();
            assertThat(session(c).isPinned()).isFalse();
            // the connection went back to the pool with the gateway's baseline application name
            try (Connection d = pg.superuser(DB); Statement st = d.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'orders-service'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            // the remembered client info is re-applied to every connection this session pins
            c.autoCommit(true);
            assertThat(c.query(sql).scalar()).isEqualTo("orders-service");
            // another session sees the baseline, whichever pooled connection it gets
            try (TestClient other = gw.client("pg")) {
                assertThat(other.query(sql).scalar()).isEqualTo("dbp-gateway/gw-pg");
            }
        }
    }

    @Test
    void manyLogicalSessionsFewBackendConnections() throws Exception {
        int sessions = 30;
        int perSession = 15;
        AtomicInteger maxBackends = new AtomicInteger();
        AtomicInteger maxPool = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            try (Connection d = pg.superuser(DB)) {
                while (sampling.get()) {
                    maxBackends.accumulateAndGet(backends(d), Math::max);
                    maxPool.accumulateAndGet(pool().totalConnections(), Math::max);
                    Thread.sleep(5);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        sampler.start();
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(sessions);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            final int sid = i;
            futures.add(ex.submit(() -> {
                try (TestClient c = gw.client("pg")) {
                    ready.countDown();
                    ready.await();
                    int ok = 0;
                    for (int j = 0; j < perSession; j++) {
                        if (j % 3 == 2) {
                            c.autoCommit(false);
                            c.update("INSERT INTO load_t (v) VALUES (?)", sid * 100 + j);
                            c.commit();
                            c.autoCommit(true);
                        } else if (j % 3 == 0) {
                            assertThat(c.update("INSERT INTO load_t (v) VALUES (?)", sid * 100 + j).updateCount()).isEqualTo(1);
                        } else {
                            assertThat(c.query("SELECT count(*) FROM load_t WHERE v = ?", sid * 100 + j - 1).scalar()).isEqualTo(1L);
                        }
                        ok++;
                    }
                    return ok;
                }
            }));
        }
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(120, TimeUnit.SECONDS);
        }
        sampling.set(false);
        sampler.join();
        ex.shutdown();
        assertThat(total).isEqualTo(sessions * perSession);
        assertThat(maxPool.get()).isLessThanOrEqualTo(5);
        assertThat(maxBackends.get()).as("PostgreSQL backends opened by the gateway").isLessThanOrEqualTo(5);
    }

    @Test
    void untypedNullParameterWorksInComparisons() {
        try (TestClient c = gw.client("pg")) {
            assertThat(c.query("SELECT count(*) FROM orders WHERE id = ?", (Object) null).scalar()).isEqualTo(0L);
            assertThat(c.query("SELECT ?::text IS NULL", (Object) null).scalar()).isEqualTo(Boolean.TRUE);
        }
    }
}
