package org.dbplatform.proxy.postgres;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.dbplatform.proxy.ProxyApp;
import org.dbplatform.proxy.config.ApplicationConfig;
import org.dbplatform.proxy.config.DatasourceQuotaConfig;
import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.config.IdentityRules;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.config.QuotaConfig;
import org.dbplatform.proxy.config.RouteConfig;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.support.Await;
import org.dbplatform.proxy.support.FakeControlPlane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real end-to-end run: PostgreSQL JDBC driver → proxy → embedded PostgreSQL, with a fake control plane
 * receiving telemetry and heartbeats and serving configuration.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresEndToEndTest {
    static final Set<String> DOCUMENTED_FIELDS = Set.of("eventId", "timestamp", "proxyId", "eventType", "listener", "engine",
            "connectionId", "clientAddr", "clientPort", "proxyLocalAddr", "proxyLocalPort", "backendHost", "backendPort",
            "requestedService", "resolvedService", "applicationId", "application", "identitySource", "datasourceId", "datasource",
            "program", "clientHost", "osUser", "dbUser", "openedAt", "closedAt", "durationMs", "bytesIn", "bytesOut", "reason");

    static EmbeddedPostgres pg;
    static FakeControlPlane cp;
    static ProxyApp app;
    static int proxyPort;
    static String proxyUrl;

    static ProxyConfigDocument config(long version, int quota) {
        RouteConfig sales = new RouteConfig("sales", "sales", "ds-sales", "db-pg-1", "127.0.0.1", pg.getPort(), "postgres", true);
        ListenerConfig listener = new ListenerConfig("postgres-main", Engine.POSTGRES, "127.0.0.1", 0, null, List.of(sales), null);
        ApplicationConfig orders = new ApplicationConfig("app-orders", "orders-service", "team-sales",
                new IdentityRules(List.of(), List.of(), List.of(), List.of("orders-service"), List.of("orders-service")));
        return new ProxyConfigDocument(version, null, List.of(listener), List.of(orders),
                List.of(new QuotaConfig("app-orders", null, "ds-sales", null, quota)),
                List.of(new DatasourceQuotaConfig("ds-sales", null, 10)));
    }

    @BeforeAll
    static void start() throws Exception {
        System.setProperty("DBP_TELEMETRY_FLUSH_MS", "200");
        pg = EmbeddedPostgres.builder().start();
        ProxyConfigDocument cfg = config(1, 2);
        cp = new FakeControlPlane("test-token", cfg);
        ProxySettings settings = ProxySettings.defaults().withListenAddress("127.0.0.1").withAdminPort(0)
                .withControlPlane(cp.url(), "test-token", "proxy-e2e").withPolling(1, 1);
        app = ProxyApp.startEmbedded(settings, cfg, null, true);
        proxyPort = app.server().listener("postgres-main").boundPort();
        proxyUrl = "jdbc:postgresql://127.0.0.1:" + proxyPort + "/sales.orders-service?sslmode=disable";
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.close();
        }
        if (cp != null) {
            cp.close();
        }
        if (pg != null) {
            pg.close();
        }
        System.clearProperty("DBP_TELEMETRY_FLUSH_MS");
    }

    static Connection proxied(String extra) throws SQLException {
        return DriverManager.getConnection(proxyUrl + extra, "postgres", "");
    }

    static Connection direct() throws SQLException {
        return pg.getPostgresDatabase().getConnection();
    }

    static int count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @Order(1)
    void sqlWorksThroughTheProxy() throws Exception {
        try (Connection c = proxied("")) {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("select 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("select current_database()")) {
                    rs.next();
                    assertThat(rs.getString(1)).as("database rewritten to the physical name").isEqualTo("postgres");
                }
                st.execute("create table orders(id int primary key, total numeric(10,2) not null)");
            }
            try (PreparedStatement ps = c.prepareStatement("insert into orders(id, total) values (?, ?)")) {
                for (int i = 1; i <= 3; i++) {
                    ps.setInt(1, i);
                    ps.setBigDecimal(2, new java.math.BigDecimal("10.50").multiply(java.math.BigDecimal.valueOf(i)));
                    ps.addBatch();
                }
                assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("insert into orders values (4, 1)");
                c.rollback();
                assertThat(count(c, "orders")).isEqualTo(3);
                st.executeUpdate("insert into orders values (4, 2)");
                c.commit();
                assertThat(count(c, "orders")).isEqualTo(4);
            }
            c.setAutoCommit(true);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select sum(total) from orders")) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("65.00");
            }
        }
        try (Connection d = direct()) {
            assertThat(count(d, "orders")).as("committed data visible on a direct connection").isEqualTo(4);
        }
    }

    @Test
    @Order(2)
    void registryShowsIdentityAndTheCorrelationKeyMatchesPgStatActivity() throws Exception {
        try (Connection c = proxied("&ApplicationName=orders-service&assumeMinServerVersion=9.4")) {
            int pid;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select pg_backend_pid()")) {
                rs.next();
                pid = rs.getInt(1);
            }
            String clientAddr;
            int clientPort;
            try (Connection d = direct(); PreparedStatement ps = d.prepareStatement(
                    "select host(client_addr), client_port from pg_stat_activity where pid = ?")) {
                ps.setInt(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    clientAddr = rs.getString(1);
                    clientPort = rs.getInt(2);
                }
            }
            Await.until(3000, () -> app.registry().size() == 1, "one live connection");
            LiveConnection live = app.registry().all().iterator().next();
            assertThat(live.state()).isEqualTo(LiveConnection.State.ESTABLISHED);
            assertThat(live.application()).isEqualTo("orders-service");
            assertThat(live.applicationId()).isEqualTo("app-orders");
            assertThat(live.identitySource().name()).isEqualTo("SERVICE_ALIAS");
            assertThat(live.datasource()).isEqualTo("sales");
            assertThat(live.datasourceId()).isEqualTo("ds-sales");
            assertThat(live.requestedService()).isEqualTo("sales.orders-service");
            assertThat(live.resolvedService()).isEqualTo("postgres");
            assertThat(live.dbUser()).isEqualTo("postgres");
            assertThat(live.program()).isEqualTo("orders-service");
            assertThat(live.backendHost()).isEqualTo("127.0.0.1");
            assertThat(live.backendPort()).isEqualTo(pg.getPort());
            assertThat(live.proxyLocalAddr()).isEqualTo(clientAddr);
            assertThat(live.proxyLocalPort()).as("pg_stat_activity.client_port is the proxy's source port").isEqualTo(clientPort);
            assertThat(live.bytesIn()).isPositive();
            assertThat(live.bytesOut()).isPositive();

            HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            String admin = "http://127.0.0.1:" + app.admin().port();
            String connections = http.send(HttpRequest.newBuilder(URI.create(admin + "/connections")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(connections).contains("\"proxyLocalPort\" : " + clientPort).contains("\"application\" : \"orders-service\"")
                    .contains("\"connectionId\" : \"" + live.id() + "\"");
            String health = http.send(HttpRequest.newBuilder(URI.create(admin + "/health")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(health).contains("\"status\" : \"UP\"").contains("\"mode\" : \"control-plane\"").contains("\"name\" : \"postgres-main\"");
            String metrics = http.send(HttpRequest.newBuilder(URI.create(admin + "/metrics")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(metrics).contains("dbp_proxy_connections_accepted_total{listener=\"postgres-main\"}")
                    .contains("dbp_proxy_connections_active{application=\"orders-service\",backend=\"127.0.0.1:" + pg.getPort()
                            + "\",datasource=\"sales\",listener=\"postgres-main\"} 1.0")
                    .contains("dbp_proxy_bytes_in_bytes_total{listener=\"postgres-main\"}")
                    .contains("dbp_proxy_backend_connect_seconds_count");
            String config = http.send(HttpRequest.newBuilder(URI.create(admin + "/config")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(config).contains("\"match\" : \"sales\"").doesNotContain("test-token");
        }
        Await.until(3000, () -> app.registry().size() == 0, "registry drained");
    }

    @Test
    @Order(3)
    void quotaRefusesTheThirdConnectionWithSqlState53300() throws Exception {
        try (Connection c1 = proxied(""); Connection c2 = proxied("".replace("sslmode=disable", "sslmode=prefer"))) {
            assertThat(c1.isValid(2)).isTrue();
            assertThat(c2.isValid(2)).isTrue();
            assertThatThrownBy(() -> proxied("").close())
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> {
                        assertThat(((SQLException) e).getSQLState()).isEqualTo("53300");
                        assertThat(e.getMessage()).contains("too many connections for orders-service/sales");
                    });
            try (Statement st = c1.createStatement(); ResultSet rs = st.executeQuery("select 2")) {
                rs.next();
                assertThat(rs.getInt(1)).as("existing connections are unaffected").isEqualTo(2);
            }
        }
        Await.until(3000, () -> app.registry().size() == 0, "slots released");
        try (Connection again = proxied("")) {
            assertThat(again.isValid(2)).as("slot available again after close").isTrue();
        }
    }

    @Test
    @Order(4)
    void sslRequestIsDeclinedAndUnknownDatabaseIsRefusedWith3D000() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + proxyPort + "/sales?sslmode=prefer", "postgres", "")) {
            assertThat(c.isValid(2)).isTrue();
            Await.until(3000, () -> app.registry().size() == 1, "live");
            assertThat(app.registry().all().iterator().next().identitySource().name()).as("no alias: no identity").isEqualTo("NONE");
        }
        assertThatThrownBy(() -> DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + proxyPort + "/nosuch?sslmode=disable", "postgres", "").close())
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("3D000"));
    }

    @Test
    @Order(5)
    void connectionEventsReachTheControlPlaneWithTheDocumentedFields() {
        Await.until(10_000, () -> cp.events("OPEN").size() >= 6 && cp.events("CLOSE").size() >= 6 && cp.events("REFUSED").size() >= 2,
                "6 OPEN, 6 CLOSE and 2 REFUSED events (have " + cp.connectionEvents.size() + ")");
        assertThat(cp.unauthorized).isEmpty();
        for (Map<String, Object> e : cp.connectionEvents) {
            assertThat(e.keySet()).as("only documented field names").isSubsetOf(DOCUMENTED_FIELDS);
            assertThat(e).containsKeys("eventId", "timestamp", "proxyId", "eventType", "listener", "engine", "connectionId",
                    "clientAddr", "clientPort", "identitySource", "application", "durationMs", "bytesIn", "bytesOut");
            assertThat(e.get("proxyId")).isEqualTo("proxy-e2e");
            assertThat(e.get("listener")).isEqualTo("postgres-main");
            assertThat(e.get("engine")).isEqualTo("POSTGRES");
            assertThat(e.get("clientAddr")).isEqualTo("127.0.0.1");
        }
        Map<String, Object> open = cp.events("OPEN").stream()
                .filter(e -> "orders-service".equals(e.get("program"))).findFirst().orElseThrow();
        assertThat(open).containsKeys("proxyLocalAddr", "proxyLocalPort", "backendHost", "backendPort", "requestedService",
                "resolvedService", "applicationId", "datasourceId", "datasource", "dbUser", "openedAt");
        assertThat(open).doesNotContainKeys("closedAt", "reason");
        assertThat(open.get("eventType")).isEqualTo("OPEN");
        assertThat(open.get("identitySource")).isEqualTo("SERVICE_ALIAS");
        assertThat(open.get("application")).isEqualTo("orders-service");
        assertThat(open.get("applicationId")).isEqualTo("app-orders");
        assertThat(open.get("datasource")).isEqualTo("sales");
        assertThat(open.get("datasourceId")).isEqualTo("ds-sales");
        assertThat(open.get("requestedService")).isEqualTo("sales.orders-service");
        assertThat(open.get("resolvedService")).isEqualTo("postgres");
        assertThat(open.get("backendHost")).isEqualTo("127.0.0.1");
        assertThat(open.get("backendPort")).isEqualTo(pg.getPort());
        assertThat(open.get("dbUser")).isEqualTo("postgres");
        assertThat((Integer) open.get("proxyLocalPort")).isPositive();
        assertThat(open.get("timestamp").toString()).endsWith("Z");

        Map<String, Object> close = cp.events("CLOSE").stream()
                .filter(e -> open.get("connectionId").equals(e.get("connectionId"))).findFirst().orElseThrow();
        assertThat(close).containsKeys("closedAt", "reason", "openedAt");
        // pgjdbc sends Terminate ('X') and then closes; PostgreSQL closes its side on Terminate, so the two EOFs race
        // and either pump direction may observe the close first
        assertThat(close.get("reason")).isIn("client closed", "backend closed");
        assertThat(close.get("proxyLocalPort")).isEqualTo(open.get("proxyLocalPort"));
        assertThat(((Number) close.get("bytesIn")).longValue()).isPositive();

        List<Map<String, Object>> refused = cp.events("REFUSED");
        assertThat(refused).anySatisfy(e -> {
            assertThat(e.get("reason")).isEqualTo("quota exceeded: orders-service/sales 2/2");
            assertThat(e.get("application")).isEqualTo("orders-service");
            assertThat(e).doesNotContainKeys("backendHost", "proxyLocalAddr");
        });
        assertThat(refused).anySatisfy(e -> {
            assertThat(e.get("reason").toString()).startsWith("unknown database 'nosuch'");
            assertThat(e.get("requestedService")).isEqualTo("nosuch");
            assertThat(e.get("identitySource")).isEqualTo("NONE");
        });
        assertThat(cp.events("BACKEND_FAILED")).isEmpty();
    }

    @Test
    @Order(6)
    void heartbeatCarriesLiveConnectionsAndConfigChangesAreHotReloaded() throws Exception {
        try (Connection c = proxied("")) {
            Await.until(5000, () -> cp.heartbeats.stream().anyMatch(h -> liveConnections(h).stream()
                    .anyMatch(l -> "sales.orders-service".equals(l.get("requestedService")))), "heartbeat with the live connection");
            Map<String, Object> hb = cp.heartbeats.get(cp.heartbeats.size() - 1);
            assertThat(hb.get("componentType")).isEqualTo("PROXY");
            assertThat(hb.get("componentId")).isEqualTo("proxy-e2e");
            assertThat(hb.get("configVersion")).isEqualTo(1);
            assertThat(hb).containsKeys("version", "host", "startedAt", "stats");
            Map<String, Object> stats = stats(hb);
            assertThat(stats).containsKeys("physicalConnections", "eventsDropped", "liveConnections");

            cp.setConfig(config(2, 5));
            Await.until(6000, () -> app.server().current().configVersion() == 2, "configuration version 2 applied");
            assertThat(app.server().listener("postgres-main").boundPort()).as("same socket kept").isEqualTo(proxyPort);
            assertThat(app.server().current().quotas().get(0).maxProxyConnections()).isEqualTo(5);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select 3")) {
                rs.next();
                assertThat(rs.getInt(1)).as("connection survived the reload").isEqualTo(3);
            }
            Await.until(5000, () -> cp.heartbeats.stream().anyMatch(h -> Integer.valueOf(2).equals(h.get("configVersion"))), "heartbeat reports version 2");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stats(Map<String, Object> hb) {
        return (Map<String, Object>) hb.get("stats");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> liveConnections(Map<String, Object> hb) {
        Object l = stats(hb).get("liveConnections");
        return l == null ? List.of() : (List<Map<String, Object>>) l;
    }
}
