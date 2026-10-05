package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
import org.dbplatform.it.support.ControlPlaneApi;
import org.dbplatform.it.support.ItExtension;
import org.dbplatform.it.support.Results;
import org.dbplatform.it.support.Stack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Scenario 2: the gateway shaded jar in control-plane mode, admin health and heartbeats. */
@ExtendWith(ItExtension.class)
@Order(2)
@DisplayName("2 Gateway in control-plane mode")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S02GatewayIT {

    @Test
    @Order(1)
    void gateway_process_starts_in_control_plane_mode_and_reaches_the_control_plane() {
        Stack s = Stack.current();
        Stack.GatewayInfo gw = s.ensureGateway();
        JsonNode health = Await.until("gateway health with control plane reachable", Duration.ofSeconds(30), () -> {
            JsonNode h = s.gatewayAdmin("/health");
            return h.path("controlPlane").path("reachable").asBoolean() ? Optional.of(h) : Optional.empty();
        });
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(health.path("gatewayId").asText()).isEqualTo(Stack.GATEWAY_ID);
        assertThat(health.path("controlPlane").path("mode").asText()).isEqualTo("control-plane");
        assertThat(health.path("controlPlane").path("configVersion").isNumber()).isTrue();
        assertThat(health.path("logicalSessions").asInt()).isZero();
        assertThat(health.path("pools").isArray()).isTrue();
        Results.note("gateway %s on port %d (admin %d), version %s, configVersion %s", Stack.GATEWAY_ID, gw.port(), gw.adminPort(),
                health.path("version").asText(), health.path("controlPlane").path("configVersion").asText());
    }

    @Test
    @Order(2)
    void heartbeat_appears_in_components_and_overview() {
        Stack s = Stack.current();
        s.ensureGateway();
        JsonNode gw = Await.until("GET /components lists " + Stack.GATEWAY_ID, Duration.ofSeconds(40), () ->
                ControlPlaneApi.items(s.cp().get("/components").json())
                        .filter(c -> Stack.GATEWAY_ID.equals(c.path("componentId").asText())).findFirst());
        assertThat(gw.path("componentType").asText()).isEqualTo("GATEWAY");
        assertThat(gw.path("healthy").asBoolean()).isTrue();
        assertThat(gw.path("version").asText()).isNotBlank();
        assertThat(gw.path("lastHeartbeat").asText()).isNotBlank();
        JsonNode overview = s.cp().get("/stats/overview").json();
        assertThat(ControlPlaneApi.stream(overview.path("componentsOnline")).map(c -> c.path("componentId").asText())).contains(Stack.GATEWAY_ID);
        Results.note("heartbeat every 2 s; components entry: version=%s host=%s healthy=%s", gw.path("version").asText(), gw.path("host").asText(), gw.path("healthy").asText());
    }

    @Test
    @Order(3)
    void metrics_endpoint_serves_prometheus_text() throws Exception {
        Stack s = Stack.current();
        Stack.GatewayInfo gw = s.ensureGateway();
        ControlPlaneApi.Response before = s.cp().getAbsolute(gw.adminUrl() + "/metrics");
        assertThat(before.status()).isEqualTo(200);
        boolean gaugesBeforeFirstSession = before.text().contains("dbp_gateway_logical_sessions");
        try (java.sql.Connection c = s.connect(Stack.DS_SALES, Stack.ORDERS)) {
            assertThat(org.dbplatform.it.support.Sql.queryLong(c, "SELECT 1")).isEqualTo(1);
            ControlPlaneApi.Response r = s.cp().getAbsolute(gw.adminUrl() + "/metrics");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.text()).contains("dbp_gateway_logical_sessions{datasource=\"sales\"}").contains("dbp_gateway_pool_max{datasource=\"sales\"}")
                    .contains("dbp_gateway_statements_total");
        }
        Results.note("Prometheus text served; per-datasource gauges %s", gaugesBeforeFirstSession ? "present before the first session"
                : "are registered lazily with the first logical session (a scrape before any session shows no dbp_gateway_* gauges)");
    }
}
