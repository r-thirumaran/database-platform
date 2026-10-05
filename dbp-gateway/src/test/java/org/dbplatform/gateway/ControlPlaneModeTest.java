package org.dbplatform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.common.controlplane.ControlPlaneClient;
import org.dbplatform.gateway.control.ControlPlaneResolver;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.protocol.messages.ErrorMessage;
import org.dbplatform.protocol.messages.Hello;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControlPlaneModeTest {

    static final String URL = "jdbc:h2:mem:cpdb;DB_CLOSE_DELAY=-1";
    static FakeControlPlane cp;
    static Gateway gateway;

    @BeforeAll
    static void start() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE USER app1 PASSWORD 'pw1' ADMIN");
            st.execute("CREATE USER app2 PASSWORD 'pw2' ADMIN");
            st.execute("CREATE TABLE customer (id INT PRIMARY KEY, email VARCHAR(100))");
            st.execute("INSERT INTO customer VALUES (1, 'a@example.org')");
        }
        cp = new FakeControlPlane(URL);
        System.setProperty("DBP_TELEMETRY_FLUSH_MS", "300");
        GatewayConfig cfg = GatewayConfig.embedded("gw-cp").withAdminPort(0)
                .withControlPlane(cp.url(), FakeControlPlane.TOKEN)
                .withIntervals(60, 1, 1, 1);
        ControlPlaneResolver resolver = new ControlPlaneResolver(new ControlPlaneClient(cp.url(), FakeControlPlane.TOKEN),
                Duration.ofSeconds(60), Duration.ofSeconds(1));
        gateway = new Gateway(cfg, resolver);
        gateway.start();
    }

    @AfterAll
    static void stop() {
        System.clearProperty("DBP_TELEMETRY_FLUSH_MS");
        if (gateway != null) {
            gateway.stop();
        }
        if (cp != null) {
            cp.close();
        }
    }

    static Map<String, String> props(String ds, String key) {
        return key == null ? Map.of(Hello.PROP_DATASOURCE, ds) : Map.of(Hello.PROP_DATASOURCE, ds, Hello.PROP_API_KEY, key);
    }

    static void await(String what, BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    @Order(1)
    void helloAuthenticatesAndResolvesThroughTheControlPlane() {
        try (TestClient c = TestClient.open(gateway.port(), props("sales", FakeControlPlane.API_KEY))) {
            assertThat(c.helloOk().engine()).isEqualTo("H2");
            assertThat(c.helloOk().serverProperties().get("userName")).isEqualToIgnoringCase("app1");
            assertThat(c.query("SELECT email FROM customer WHERE id = ?", 1).scalar()).isEqualTo("a@example.org");
        }
        List<FakeControlPlane.Request> auth = cp.requests("POST", "/api/v1/internal/auth/application");
        assertThat(auth).isNotEmpty();
        assertThat(auth.get(0).token()).isEqualTo(FakeControlPlane.TOKEN);
        assertThat(auth.get(0).body()).contains("\"apiKey\":\"" + FakeControlPlane.API_KEY + "\"");
        List<FakeControlPlane.Request> resolve = cp.requests("GET", "/api/v1/internal/resolve/datasource/sales");
        assertThat(resolve).isNotEmpty();
        assertThat(resolve.get(0).path()).isEqualTo("/api/v1/internal/resolve/datasource/sales?applicationId=app-1");
        List<FakeControlPlane.Request> material = cp.requests("GET", "/api/v1/internal/credentials/cred-1/material");
        assertThat(material).hasSize(1);
        assertThat(material.get(0).token()).isEqualTo(FakeControlPlane.TOKEN);
        PhysicalPool pool = gateway.pools().poolsFor("sales").get(0);
        assertThat(pool.key()).isEqualTo("db-1@v1");
        assertThat(pool.settings().username()).isEqualTo("app1");
        assertThat(pool.maxConnections()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void authAndGrantFailuresAre08004() {
        try (TestClient c = TestClient.connect(gateway.port())) {
            ErrorMessage e = c.helloError(props("sales", "dbp_wrong"));
            assertThat(e.sqlState()).isEqualTo("08004");
            assertThat(e.fatal()).isTrue();
        }
        try (TestClient c = TestClient.connect(gateway.port())) {
            assertThat(c.helloError(props("sales", null)).sqlState()).isEqualTo("08004");
        }
        try (TestClient c = TestClient.connect(gateway.port())) {
            ErrorMessage e = c.helloError(props("forbidden", FakeControlPlane.API_KEY));
            assertThat(e.sqlState()).isEqualTo("08004");
            assertThat(e.message()).contains("not authorised");
        }
        try (TestClient c = TestClient.connect(gateway.port())) {
            assertThat(c.helloError(props("missing", FakeControlPlane.API_KEY)).sqlState()).isEqualTo("08004");
        }
        // negative results are cached: only one auth call for the wrong key
        assertThat(cp.requests("POST", "/api/v1/internal/auth/application").stream()
                .filter(r -> r.body().contains("dbp_wrong")).count()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void maxLogicalConnectionsPerGrantIsEnforced() {
        try (TestClient a = TestClient.open(gateway.port(), props("sales", FakeControlPlane.API_KEY));
             TestClient b = TestClient.open(gateway.port(), props("sales", FakeControlPlane.API_KEY));
             TestClient third = TestClient.connect(gateway.port())) {
            ErrorMessage e = third.helloError(props("sales", FakeControlPlane.API_KEY));
            assertThat(e.sqlState()).isEqualTo("08004");
            assertThat(e.message()).contains("too many logical connections");
            a.ping();
            b.ping();
        }
    }

    @Test
    @Order(4)
    void queryEventsPoolStatsAndHeartbeatsArePosted() throws Exception {
        cp.queryEvents.clear();
        String sessionId;
        try (TestClient c = TestClient.open(gateway.port(), props("sales", FakeControlPlane.API_KEY))) {
            sessionId = c.helloOk().sessionId();
            c.ok(new org.dbplatform.protocol.messages.SetClientInfo("ApplicationName", "orders-service"));
            c.query("SELECT email FROM customer WHERE id = ?", 1);
            c.autoCommit(false);
            c.update("UPDATE customer SET email = ? WHERE id = ?", "b@example.org", 1);
            c.commit();
            try {
                c.exec("SELECT * FROM nope");
            } catch (TestClient.SqlError ignored) {
                // expected
            }
            gateway.telemetry().flush();
            await("3 query events", () -> cp.queryEvents.size() >= 3, 10_000);
        }
        List<FakeControlPlane.Request> posts = cp.requests("POST", "/api/v1/internal/telemetry/queries");
        assertThat(posts.get(0).token()).isEqualTo(FakeControlPlane.TOKEN);

        JsonNode select = cp.queryEvents.stream().filter(n -> n.path("operation").asText().equals("SELECT")
                && n.path("success").asBoolean()).findFirst().orElseThrow();
        for (String f : List.of("eventId", "timestamp", "gatewayId", "sessionId", "applicationId", "application", "team",
                "datasource", "databaseId", "engine", "sqlHash", "sqlNormalized", "operation", "tables", "routines",
                "columns", "durationMs", "rows", "success", "errorCode", "pinned", "poolMode", "clientInfo")) {
            assertThat(select.has(f)).as("field " + f).isTrue();
        }
        assertThat(select.get("gatewayId").asText()).isEqualTo("gw-cp");
        assertThat(select.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(select.get("applicationId").asText()).isEqualTo("app-1");
        assertThat(select.get("application").asText()).isEqualTo("orders-service");
        assertThat(select.get("team").asText()).isEqualTo("sales");
        assertThat(select.get("datasource").asText()).isEqualTo("sales");
        assertThat(select.get("databaseId").asText()).isEqualTo("db-1");
        assertThat(select.get("engine").asText()).isEqualTo("H2");
        assertThat(select.get("sqlNormalized").asText()).containsIgnoringCase("select email from customer where id = ?");
        assertThat(select.get("sqlHash").asText()).hasSize(64);
        assertThat(select.get("tables").get(0).get("name").asText()).isEqualToIgnoringCase("customer");
        assertThat(select.get("tables").get(0).get("access").asText()).isEqualTo("READ");
        assertThat(select.get("rows").asLong()).isEqualTo(1);
        assertThat(select.get("pinned").asBoolean()).isFalse();
        assertThat(select.get("poolMode").asText()).isEqualTo("TRANSACTION");
        assertThat(select.get("clientInfo").get("ApplicationName").asText()).isEqualTo("orders-service");

        JsonNode update = cp.queryEvents.stream().filter(n -> n.path("operation").asText().equals("UPDATE")).findFirst().orElseThrow();
        assertThat(update.get("tables").get(0).get("access").asText()).isEqualTo("WRITE");
        assertThat(update.get("rows").asLong()).isEqualTo(1);
        assertThat(update.get("columns").toString()).containsIgnoringCase("email");

        JsonNode failed = cp.queryEvents.stream().filter(n -> !n.path("success").asBoolean()).findFirst().orElseThrow();
        assertThat(failed.get("sqlState").asText()).isEqualTo("42S02");
        assertThat(failed.get("errorCode").asInt()).isNotZero();
        assertThat(failed.get("errorMessage").asText()).containsIgnoringCase("nope");

        await("pool stats", () -> !cp.poolStats.isEmpty(), 10_000);
        JsonNode ps = cp.poolStats.get(cp.poolStats.size() - 1);
        for (String f : List.of("timestamp", "gatewayId", "datasource", "datasourceId", "databaseId", "engine", "active",
                "idle", "waiting", "total", "max", "logicalSessions", "pinnedSessions", "credentialVersion")) {
            assertThat(ps.has(f)).as("PoolStats field " + f).isTrue();
        }
        assertThat(ps.get("max").asInt()).isEqualTo(3);
        assertThat(ps.get("datasourceId").asText()).isEqualTo("ds-1");
        assertThat(ps.get("credentialVersion").asInt()).isEqualTo(1);

        await("heartbeat", () -> !cp.heartbeats.isEmpty(), 10_000);
        JsonNode hb = cp.heartbeats.get(0);
        assertThat(hb.get("componentType").asText()).isEqualTo("GATEWAY");
        assertThat(hb.get("componentId").asText()).isEqualTo("gw-cp");
        assertThat(hb.has("version")).isTrue();
        assertThat(hb.has("host")).isTrue();
        assertThat(hb.has("startedAt")).isTrue();
        assertThat(hb.get("stats").has("logicalSessions")).isTrue();
        assertThat(hb.get("stats").has("physicalConnections")).isTrue();
        assertThat(hb.get("stats").has("eventsDropped")).isTrue();
        assertThat(gateway.resolver().configVersion()).contains(1L);
    }

    @Test
    @Order(5)
    void credentialRotationCreatesNewPoolAndDrainsTheOld() throws Exception {
        try (TestClient old = TestClient.open(gateway.port(), props("sales", FakeControlPlane.API_KEY))) {
            old.autoCommit(false);
            old.query("SELECT 1"); // pins a connection of pool v1
            PhysicalPool v1 = gateway.pools().poolsFor("sales").get(0);
            assertThat(v1.key()).isEqualTo("db-1@v1");

            cp.rotateCredential("app2", "pw2", 2);
            await("config version 2 observed", () -> gateway.resolver().configVersion().orElse(0L) == 2L, 10_000);

            try (TestClient fresh = TestClient.open(gateway.port(), props("sales", FakeControlPlane.API_KEY))) {
                assertThat(fresh.helloOk().serverProperties().get("userName")).isEqualToIgnoringCase("app2");
                assertThat(fresh.query("SELECT USER()").scalar().toString()).isEqualToIgnoringCase("app2");
            }
            List<PhysicalPool> pools = gateway.pools().poolsFor("sales");
            assertThat(pools).extracting(PhysicalPool::key).contains("db-1@v2");
            assertThat(v1.isDraining()).as("old pool drains while a session is still pinned").isTrue();
            assertThat(v1.isClosed()).isFalse();
            assertThat(cp.requests("GET", "/api/v1/internal/credentials/cred-1/material")).hasSize(2);

            // the old session's next pin after COMMIT goes to the new pool
            old.commit();
            old.query("SELECT 1");
            assertThat(old.query("SELECT USER()").scalar().toString()).isEqualToIgnoringCase("app2");
            old.rollback();
        }
        await("old pool closed", () -> gateway.pools().poolsFor("sales").stream().allMatch(p -> p.key().equals("db-1@v2")), 10_000);
        assertThat(gateway.pools().pools()).hasSize(1);
        assertThat(gateway.pools().pools().iterator().next().settings().credentialVersion()).isEqualTo(2);
    }
}
