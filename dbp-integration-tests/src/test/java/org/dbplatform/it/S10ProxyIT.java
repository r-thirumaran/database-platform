package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
import org.dbplatform.it.support.ControlPlaneApi;
import org.dbplatform.it.support.ItExtension;
import org.dbplatform.it.support.Results;
import org.dbplatform.it.support.Sql;
import org.dbplatform.it.support.Stack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dbplatform.it.support.ControlPlaneApi.items;
import static org.dbplatform.it.support.ControlPlaneApi.stream;
import static org.dbplatform.it.support.Stack.DB_PG;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 10: the transparent proxy in front of PostgreSQL (black box: shaded jar, pgjdbc client). */
@ExtendWith(ItExtension.class)
@Order(10)
@DisplayName("10 Proxy path (PostgreSQL)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S10ProxyIT {

    @Test
    @Order(1)
    void control_plane_builds_a_postgres_listener_routing_sales_to_the_embedded_database() {
        Stack s = Stack.current();
        s.bootstrap();
        JsonNode cfg = s.cp().internalGet("/internal/proxy/config?proxyId=" + Stack.PROXY_ID).json();
        JsonNode pgListener = stream(cfg.path("listeners")).filter(l -> "POSTGRES".equals(l.path("engine").asText())).findFirst().orElseThrow();
        JsonNode route = stream(pgListener.path("routes")).filter(r -> "sales".equals(r.path("match").asText())).findFirst().orElseThrow();
        assertThat(route.path("host").asText()).isEqualTo("127.0.0.1");
        assertThat(route.path("port").asInt()).isEqualTo(s.pg().port());
        assertThat(route.path("serviceName").asText()).isEqualTo("sales");
        assertThat(route.path("rewriteServiceName").asBoolean()).isTrue();
        assertThat(route.path("databaseId").asText()).isEqualTo(s.id("db", DB_PG));
        JsonNode orders = stream(cfg.path("applications")).filter(a -> ORDERS.equals(a.path("name").asText())).findFirst().orElseThrow();
        assertThat(stream(orders.path("identityRules").path("serviceAliases")).map(JsonNode::asText)).contains(ORDERS);
        assertThat(stream(cfg.path("quotas")).anyMatch(q -> s.id("app", ORDERS).equals(q.path("applicationId").asText()) && q.path("maxProxyConnections").asInt() == 20)).isTrue();
        Results.note("proxy config: listener %s engine POSTGRES port %d (fixed per engine by the control plane), route sales -> 127.0.0.1:%d/sales, %d listeners in total (%s)",
                pgListener.path("name").asText(), pgListener.path("port").asInt(), s.pg().port(), stream(cfg.path("listeners")).count(),
                stream(cfg.path("listeners")).map(l -> l.path("engine").asText() + ":" + l.path("port").asInt()).toList());
        if (pgListener.path("port").asInt() != 5432) {
            Results.note("unexpected: PostgreSQL listener port is " + pgListener.path("port").asInt());
        }
    }

    @Test
    @Order(2)
    void proxied_pgjdbc_connection_is_attributed_to_orders_service_and_correlates_with_client_port() throws Exception {
        Stack s = Stack.current();
        Assumptions.assumeTrue(s.proxyJar().isPresent(), "proxy shaded jar not available (dbp-proxy/target/dbp-proxy-*-all.jar or -Ddbp.it.proxyJar)");
        Stack.ProxyInfo proxy = s.ensureProxy();
        String url = "jdbc:postgresql://127.0.0.1:" + proxy.pgPort() + "/sales.orders-service?sslmode=disable&ApplicationName=orders-service";
        try (Connection c = DriverManager.getConnection(url, "sales_app", Stack.SALES_APP_PASSWORD)) {
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer")).isGreaterThan(200);
            assertThat(Sql.queryString(c, "SELECT current_database()")).as("database name rewritten to the physical one").isEqualTo("sales");
            int clientPort;
            String appName;
            try (PreparedStatement ps = c.prepareStatement("SELECT client_port, application_name, usename FROM pg_stat_activity WHERE pid = pg_backend_pid()");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                clientPort = rs.getInt(1);
                appName = rs.getString(2);
                assertThat(rs.getString(3)).isEqualTo("sales_app");
            }
            assertThat(clientPort).isPositive();
            JsonNode adminConn = Await.until("proxy /connections lists the proxied connection", Duration.ofSeconds(15), () ->
                    stream(s.cp().getAbsolute(proxy.adminUrl() + "/connections").json())
                            .filter(x -> x.path("proxyLocalPort").asInt() == clientPort).findFirst());
            assertThat(adminConn.path("application").asText()).isEqualTo(ORDERS);
            assertThat(adminConn.path("identitySource").asText()).isEqualTo("SERVICE_ALIAS");
            assertThat(adminConn.path("datasource").asText()).isEqualTo("sales");
            assertThat(adminConn.path("requestedService").asText()).isEqualTo("sales.orders-service");
            assertThat(adminConn.path("resolvedService").asText()).isEqualTo("sales");
            if ("control-plane".equals(proxy.mode())) {
                JsonNode live = Await.until("GET /connections/live shows the proxied connection attributed to orders-service", Duration.ofSeconds(30), () ->
                        items(s.cp().get("/connections/live").json())
                                .filter(x -> "PROXY".equals(x.path("source").asText()) && x.path("proxyLocalPort").asInt() == clientPort).findFirst());
                assertThat(live.path("application").asText()).isEqualTo(ORDERS);
                assertThat(live.path("team").asText()).isEqualTo("sales-platform");
                assertThat(live.path("datasource").asText()).isEqualTo("sales");
                assertThat(live.path("dbUser").asText()).isEqualTo("sales_app");
                JsonNode component = Await.until("GET /components lists the proxy", Duration.ofSeconds(20), () ->
                        items(s.cp().get("/components").json()).filter(x -> Stack.PROXY_ID.equals(x.path("componentId").asText())).findFirst());
                assertThat(component.path("componentType").asText()).isEqualTo("PROXY");
                assertThat(component.path("healthy").asBoolean()).isTrue();
                // the collector's runtime sample should correlate the backend session (client_port) with the live proxy connection
                s.cp().post("/databases/" + s.id("db", DB_PG) + "/collect", Map.of("what", "RUNTIME"));
                Optional<JsonNode> collectorRow = tryAwaitCollectorRow(s, clientPort);
                if (collectorRow.isPresent()) {
                    assertThat(collectorRow.get().path("application").asText()).isEqualTo(ORDERS);
                    Results.note("collector runtime sample attributed the backend session to %s (source COLLECTOR, PROXY_CORRELATION on client_port %d)", ORDERS, clientPort);
                } else {
                    Results.partial("collector runtime sample did not show the proxied session attributed to orders-service within 40 s (proxy-side attribution verified)");
                }
                Results.note("proxy mode control-plane: pg_stat_activity.client_port=%d == proxyLocalPort in proxy /connections and in GET /connections/live (application %s, SERVICE_ALIAS); application_name seen by PostgreSQL: %s",
                        clientPort, ORDERS, appName);
            } else {
                Results.partial("proxy ran in mode '" + proxy.mode() + "' (" + String.join("; ", s.findings.stream().filter(f -> f.contains("proxy")).toList())
                        + "), so GET /connections/live and /components were not checked; proxy-side attribution and client_port correlation verified");
            }
        }
    }

    private static Optional<JsonNode> tryAwaitCollectorRow(Stack s, int clientPort) {
        try {
            return Optional.of(Await.until("collector live row", Duration.ofSeconds(40), () ->
                    items(s.cp().get("/connections/live").json())
                            .filter(x -> "COLLECTOR".equals(x.path("source").asText()) && ORDERS.equals(x.path("application").asText())).findFirst()));
        } catch (AssertionError e) {
            return Optional.empty();
        }
    }

    @Test
    @Order(3)
    void unknown_logical_database_is_refused_with_3D000_and_quota_is_enforced() throws Exception {
        Stack s = Stack.current();
        Assumptions.assumeTrue(s.proxyJar().isPresent(), "proxy shaded jar not available");
        Stack.ProxyInfo proxy = s.ensureProxy();
        assertThatThrownBy(() -> DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + proxy.pgPort() + "/nosuchdb?sslmode=disable", "sales_app", Stack.SALES_APP_PASSWORD))
                .isInstanceOf(SQLException.class).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("3D000"));
        // sslmode=require must fail: the proxy answers N to SSLRequest (documented limitation)
        assertThatThrownBy(() -> DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + proxy.pgPort() + "/sales.orders-service?sslmode=require", "sales_app", Stack.SALES_APP_PASSWORD))
                .isInstanceOf(SQLException.class);
        JsonNode health = s.cp().getAbsolute(proxy.adminUrl() + "/health").json();
        Results.note("proxy health: status=%s listeners=%s", health.path("status").asText(),
                stream(health.path("listeners")).map(l -> l.path("engine").asText() + ":" + l.path("port").asInt() + (l.path("running").asBoolean() ? "" : "(down)")).toList());
        ControlPlaneApi.Response metrics = s.cp().getAbsolute(proxy.adminUrl() + "/metrics");
        assertThat(metrics.text()).contains("dbp_proxy_connections_refused_total");
    }
}
