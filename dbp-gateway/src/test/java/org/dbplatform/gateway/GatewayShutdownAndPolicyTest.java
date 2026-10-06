package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.session.LogicalSession;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteBatch;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.StatementKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle and policy behaviour that needs its own gateway instance: graceful shutdown ordering, read-only grants
 * and per-datasource metrics before the first session.
 */
public class GatewayShutdownAndPolicyTest {

    static final String URL = "jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1";

    @BeforeAll
    static void schema() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, v VARCHAR(20))");
            st.execute("MERGE INTO t KEY (id) VALUES (1, 'one')");
            // Thread.sleep has two one-argument overloads, which H2 refuses: alias a method of this (public) class
            st.execute("CREATE ALIAS IF NOT EXISTS SLEEP FOR \"" + GatewayShutdownAndPolicyTest.class.getName() + ".sleepMillis\"");
        }
    }

    /** Target of the H2 {@code SLEEP} alias (the in-memory database runs in this JVM). */
    public static int sleepMillis(long millis) throws InterruptedException {
        Thread.sleep(millis);
        return 0;
    }

    static GatewayFixture start(String id, int graceSeconds, List<StaticConfig.ApplicationConfig> apps) {
        GatewayConfig cfg = GatewayConfig.embedded(id).withAdminPort(0).withShutdownGraceSeconds(graceSeconds);
        return GatewayFixture.start(cfg, new StaticConfig(id, List.of(
                StaticConfig.DatasourceConfig.of("h2", "H2", URL, "sa", "", "TRANSACTION", 3).withConnectionTimeoutMs(500),
                StaticConfig.DatasourceConfig.of("unused", "H2", URL, "sa", "", "TRANSACTION", 2)), apps));
    }

    static LogicalSession session(GatewayFixture gw, TestClient c) {
        return gw.gateway.sessions().sessions().stream().filter(s -> s.id().equals(c.helloOk().sessionId())).findFirst().orElseThrow();
    }

    /** Physical H2 sessions on the test database other than the one asking. */
    static long h2Sessions() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS")) {
            rs.next();
            return rs.getLong(1) - 1;
        }
    }

    static long count(String where) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void awaitNoH2Sessions() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (h2Sessions() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(h2Sessions()).as("no physical connection survived the pool shutdown").isZero();
    }

    // ------------------------------------------------------------------ shutdown ordering

    @Test
    void stopClosesIdleSessionsThroughTheirHandlersAndLeaksNoConnection() throws Exception {
        try (GatewayFixture gw = start("gw-stop", 1, List.of())) {
            TestClient tx = gw.client("h2");
            tx.autoCommit(false);
            tx.update("INSERT INTO t VALUES (?, ?)", 100, "uncommitted"); // pinned, transaction open, idle
            TestClient idle = gw.client("h2");
            idle.query("SELECT 1"); // not pinned, idle
            PhysicalPool pool = gw.gateway.pools().poolsFor("h2").get(0);
            assertThat(pool.activeConnections()).isEqualTo(1);
            assertThat(gw.gateway.sessions().size()).isEqualTo(2);
            assertThat(h2Sessions()).isGreaterThanOrEqualTo(1);

            long t0 = System.nanoTime();
            assertThatCode(gw.gateway::stop).doesNotThrowAnyException();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("nothing in flight: the grace period is not consumed").isLessThan(10_000);

            assertThat(pool.isClosed()).isTrue();
            assertThat(gw.gateway.pools().pools()).isEmpty();
            assertThat(gw.gateway.sessions().size()).as("the handlers unregistered their sessions themselves").isZero();
            // the open transaction was rolled back when the handler released its connection
            assertThat(count("id = 100")).isZero();
            awaitNoH2Sessions();
            // the clients were disconnected
            assertThatThrownBy(tx::ping).isInstanceOfAny(UncheckedIOException.class, TestClient.SqlError.class);
            tx.closeSocket();
            idle.closeSocket();
        }
    }

    @Test
    void stopWaitsForAnInFlightStatementThenLetsItsHandlerReleaseTheConnection() throws Exception {
        try (GatewayFixture gw = start("gw-stop-slow", 1, List.of())) {
            TestClient slow = gw.client("h2");
            slow.autoCommit(false);
            slow.update("INSERT INTO t VALUES (?, ?)", 200, "slow");
            ExecutorService ex = Executors.newSingleThreadExecutor();
            try {
                Future<Object> inFlight = ex.submit(() -> {
                    try {
                        return slow.exec("CALL SLEEP(2500)");
                    } catch (RuntimeException e) {
                        return e;
                    }
                });
                long deadline = System.currentTimeMillis() + 5000;
                while (gw.gateway.sessions().executing() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(gw.gateway.sessions().executing()).as("the slow statement is in flight").isEqualTo(1);

                long t0 = System.nanoTime();
                assertThatCode(gw.gateway::stop).doesNotThrowAnyException();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                // the grace period (1 s) was waited for the statement; afterwards the socket was closed and the handler
                // got time to finish its own cleanup once the driver call returned (~2.5 s)
                assertThat(ms).isBetween(1000L, 20_000L);

                Object outcome = inFlight.get(15, TimeUnit.SECONDS);
                assertThat(outcome).as("the client lost its connection instead of receiving the result")
                        .isInstanceOfAny(UncheckedIOException.class, TestClient.SqlError.class);
            } finally {
                ex.shutdownNow();
            }
            assertThat(gw.gateway.sessions().size()).isZero();
            assertThat(gw.gateway.pools().pools()).isEmpty();
            assertThat(count("id = 200")).as("the transaction of the slow session was rolled back").isZero();
            awaitNoH2Sessions();
            slow.closeSocket();
        }
    }

    // ------------------------------------------------------------------ read-only grants

    @Test
    void readOnlyGrantRejectsWritesBeforeTouchingTheDatabase() throws Exception {
        StaticConfig.ApplicationConfig reader = new StaticConfig.ApplicationConfig("reader", "ro-key", "team",
                List.of(new StaticConfig.GrantConfig("h2", null, true, null)));
        StaticConfig.ApplicationConfig writer = new StaticConfig.ApplicationConfig("writer", "rw-key", "team",
                List.of(StaticConfig.GrantConfig.of("h2")));
        try (GatewayFixture gw = start("gw-ro", 1, List.of(reader, writer));
             TestClient ro = TestClient.open(gw.port(), Map.of(Hello.PROP_DATASOURCE, "h2", Hello.PROP_API_KEY, "ro-key"));
             TestClient rw = TestClient.open(gw.port(), Map.of(Hello.PROP_DATASOURCE, "h2", Hello.PROP_API_KEY, "rw-key"))) {
            PhysicalPool pool = gw.gateway.pools().poolsFor("h2").get(0);
            int borrowedBefore = pool.pinnedSessions();
            for (String sql : List.of("UPDATE t SET v = 'x' WHERE id = 1", "INSERT INTO t VALUES (9, 'nine')",
                    "DELETE FROM t WHERE id = 1", "MERGE INTO t KEY (id) VALUES (1, 'merged')", "CREATE TABLE t2 (id INT)",
                    "DROP TABLE t", "ALTER TABLE t ADD COLUMN w INT")) {
                assertThatThrownBy(() -> ro.exec(sql)).as(sql).isInstanceOf(TestClient.SqlError.class)
                        .satisfies(e -> {
                            assertThat(((TestClient.SqlError) e).sqlState()).isEqualTo("25006");
                            assertThat(((TestClient.SqlError) e).fatal()).isFalse();
                        });
            }
            // rejected before any physical call: the read-only session never borrowed a connection
            assertThat(session(gw, ro).isPinned()).isFalse();
            assertThat(session(gw, ro).statements()).isZero();
            assertThat(pool.pinnedSessions()).isEqualTo(borrowedBefore);
            // prepared statements and batches are covered too
            int stmt = ro.prepare("UPDATE t SET v = ? WHERE id = ?", StatementKind.PREPARED);
            assertThatThrownBy(() -> ro.executePrepared(stmt, Execute.Expect.UPDATE, "x", 1))
                    .satisfies(e -> assertThat(((TestClient.SqlError) e).sqlState()).isEqualTo("25006"));
            assertThatThrownBy(() -> ro.batch(ExecuteBatch.ofStatements(List.of("SELECT 1", "UPDATE t SET v = 'x'"))))
                    .satisfies(e -> assertThat(((TestClient.SqlError) e).sqlState()).isEqualTo("25006"));
            assertThatThrownBy(() -> ro.batch(ExecuteBatch.ofPrepared(stmt, StatementKind.PREPARED, List.of(List.<Object>of("x", 1)))))
                    .satisfies(e -> assertThat(((TestClient.SqlError) e).sqlState()).isEqualTo("25006"));
            // reads work, also inside a transaction, and the data is intact
            assertThat(ro.query("SELECT v FROM t WHERE id = 1").scalar()).isEqualTo("one");
            ro.autoCommit(false);
            assertThat(ro.query("SELECT COUNT(*) FROM t").scalar()).isEqualTo(1L);
            assertThatThrownBy(() -> ro.update("UPDATE t SET v = 'x' WHERE id = 1"))
                    .satisfies(e -> assertThat(((TestClient.SqlError) e).sqlState()).isEqualTo("25006"));
            ro.rollback();
            ro.autoCommit(true);
            assertThat(count("v = 'one'")).isEqualTo(1);
            // the writer on the same datasource is unaffected
            assertThat(rw.update("UPDATE t SET v = 'one' WHERE id = 1").updateCount()).isEqualTo(1L);
            assertThat(gw.adminGet("/metrics")).contains("sqlstate=\"25006\"");
        }
    }

    // ------------------------------------------------------------------ metrics before the first session

    @Test
    void perDatasourceGaugesExistBeforeTheFirstSession() throws Exception {
        try (GatewayFixture gw = start("gw-metrics", 1, List.of())) {
            String metrics = gw.adminGet("/metrics");
            for (String ds : List.of("h2", "unused")) {
                for (String gauge : List.of("dbp_gateway_logical_sessions", "dbp_gateway_pinned_sessions",
                        "dbp_gateway_pool_active", "dbp_gateway_pool_idle", "dbp_gateway_pool_waiting",
                        "dbp_gateway_pool_connections", "dbp_gateway_pool_max")) {
                    assertThat(gaugeValue(metrics, gauge, ds)).as("%s{datasource=%s} at startup", gauge, ds).isZero();
                }
            }
            try (TestClient c = gw.client("h2")) {
                c.query("SELECT 1");
                String after = gw.adminGet("/metrics");
                assertThat(gaugeValue(after, "dbp_gateway_logical_sessions", "h2")).isEqualTo(1.0);
                assertThat(gaugeValue(after, "dbp_gateway_pool_max", "h2")).isEqualTo(3.0);
                assertThat(gaugeValue(after, "dbp_gateway_pool_connections", "h2")).isGreaterThanOrEqualTo(1.0);
                assertThat(after).doesNotContain("dbp_gateway_pool{"); // the old pool.total gauge lost its suffix on export
                assertThat(gaugeValue(after, "dbp_gateway_logical_sessions", "unused")).isZero();
            }
        }
    }

    static double gaugeValue(String scrape, String name, String datasource) {
        Pattern p = Pattern.compile("^" + Pattern.quote(name) + "\\{[^}]*datasource=\"" + Pattern.quote(datasource)
                + "\"[^}]*\\}\\s+(\\S+)\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(scrape);
        assertThat(m.find()).as("%s{datasource=\"%s\"} is exposed:%n%s", name, datasource, scrape).isTrue();
        return Double.parseDouble(m.group(1));
    }
}
