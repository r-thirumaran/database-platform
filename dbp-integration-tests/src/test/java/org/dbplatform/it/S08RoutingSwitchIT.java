package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
import org.dbplatform.it.support.ItExtension;
import org.dbplatform.it.support.Results;
import org.dbplatform.it.support.Sql;
import org.dbplatform.it.support.Stack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.dbplatform.it.support.ControlPlaneApi.stream;
import static org.dbplatform.it.support.Stack.BATCH;
import static org.dbplatform.it.support.Stack.DB_ALT;
import static org.dbplatform.it.support.Stack.DB_PG;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 8: routing rules and datasource switch through the control plane; H2 as a second engine. */
@ExtendWith(ItExtension.class)
@Order(8)
@DisplayName("8 Routing rule, datasource switch, second engine")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S08RoutingSwitchIT {

    /** Database a NEW logical connection of the application lands on (retried: the gateway re-reads the configuration within ~1 s). */
    private static String databaseOfNewConnection(Stack s, String app, String expected) {
        return Await.until("new " + app + " connection lands on " + expected, Duration.ofSeconds(30), () -> {
            try (Connection c = s.connect(DS_SALES, app)) {
                String db = Sql.queryString(c, "SELECT current_database()");
                return expected.equals(db) ? Optional.of(db) : Optional.empty();
            } catch (SQLException e) {
                throw new AssertionError(Sql.describe(e), e);
            }
        });
    }

    private static String resolvedDatabase(Stack s, String app) {
        return s.cp().internalGet("/internal/resolve/datasource/" + DS_SALES + "?applicationId=" + s.id("app", app)).json().path("database").path("name").asText();
    }

    @Test
    @Order(1)
    void routing_rule_moves_reporting_batch_to_sales_alt_while_orders_service_stays_on_sales() throws Exception {
        Stack s = Stack.current();
        s.ensureGateway();
        String dsId = s.id("ds", DS_SALES);
        assertThat(resolvedDatabase(s, BATCH)).isEqualTo(DB_PG);
        JsonNode ds = s.cp().post("/datasources/" + dsId + "/routing-rules",
                Map.of("priority", 5, "applicationId", s.id("app", BATCH), "databaseId", s.id("db", DB_ALT), "readOnly", false, "enabled", true)).json();
        JsonNode rule = stream(ds.path("routingRules")).filter(r -> s.id("app", BATCH).equals(r.path("applicationId").asText())).findFirst().orElseThrow();
        assertThat(resolvedDatabase(s, BATCH)).isEqualTo(DB_ALT);
        assertThat(resolvedDatabase(s, ORDERS)).isEqualTo(DB_PG);
        assertThat(databaseOfNewConnection(s, BATCH, "sales_alt")).isEqualTo("sales_alt");
        try (Connection c = s.connect(DS_SALES, BATCH)) {
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer")).as("sales_alt has the 3 marker customers").isEqualTo(3);
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        try (Connection c = s.connect(DS_SALES, ORDERS)) {
            assertThat(Sql.queryString(c, "SELECT current_database()")).isEqualTo("sales");
            assertThat(Sql.queryLong(c, "SELECT count(*) FROM customer")).as("demo data (200 customers; earlier scenarios add more)").isGreaterThanOrEqualTo(200);
        }
        JsonNode pools = s.gatewayAdmin("/pools");
        assertThat(stream(pools).map(p -> p.path("jdbcUrl").asText()).toList()).anyMatch(u -> u.endsWith("/sales_alt")).anyMatch(u -> u.endsWith("/sales"));
        s.cp().delete("/datasources/" + dsId + "/routing-rules/" + rule.path("id").asText()).expect(200, 204);
        assertThat(resolvedDatabase(s, BATCH)).isEqualTo(DB_PG);
        assertThat(databaseOfNewConnection(s, BATCH, "sales")).isEqualTo("sales");
        Results.note("rule(priority 5, reporting-batch -> sales-alt): batch connections hit sales_alt (3 customers), orders-service stayed on sales; rule removed -> back on sales; gateway pools: %s",
                stream(pools).map(p -> p.path("key").asText()).toList());
    }

    @Test
    @Order(2)
    void switch_moves_new_connections_while_a_pinned_transaction_keeps_its_database() throws Exception {
        Stack s = Stack.current();
        String dsId = s.id("ds", DS_SALES);
        try (Connection pinned = s.connect(DS_SALES, ORDERS)) {
            pinned.setAutoCommit(false);
            assertThat(Sql.queryString(pinned, "SELECT current_database()")).isEqualTo("sales");
            JsonNode switched = s.cp().post("/datasources/" + dsId + "/switch", Map.of("databaseId", s.id("db", DB_ALT), "note", "it: switch to sales-alt")).json();
            assertThat(switched.path("currentDatabaseId").asText()).isEqualTo(s.id("db", DB_ALT));
            JsonNode events = s.cp().get("/migration-events?datasourceId=" + dsId).json();
            assertThat(stream(events).anyMatch(e -> s.id("db", DB_ALT).equals(e.path("toDatabaseId").asText()) && s.id("db", DB_PG).equals(e.path("fromDatabaseId").asText()))).isTrue();
            assertThat(databaseOfNewConnection(s, ORDERS, "sales_alt")).isEqualTo("sales_alt");
            assertThat(Sql.queryString(pinned, "SELECT current_database()")).as("open transaction stays on its physical connection").isEqualTo("sales");
            pinned.commit();
            String afterCommit = Sql.queryString(pinned, "SELECT current_database()");
            Results.note("after COMMIT the existing logical connection re-resolved to %s on its next pin (README: existing sessions follow at the next pin)", afterCommit);
            pinned.setAutoCommit(true);
        }
        s.cp().post("/datasources/" + dsId + "/switch", Map.of("databaseId", s.id("db", DB_PG), "note", "it: switch back")).json();
        assertThat(databaseOfNewConnection(s, ORDERS, "sales")).isEqualTo("sales");
        assertThat(s.cp().get("/datasources/" + dsId).json().path("currentDatabaseId").asText()).isEqualTo(s.id("db", DB_PG));
        assertThat(stream(s.cp().get("/migration-events?datasourceId=" + dsId).json()).count()).isGreaterThanOrEqualTo(2);
        Results.note("switch sales -> sales-alt -> sales: new connections followed each switch within the 1 s config poll; 2 MigrationEvents recorded");
    }

    @Test
    @Order(3)
    void h2_in_oracle_mode_is_reachable_through_a_static_mode_gateway() throws Exception {
        Stack s = Stack.current();
        Stack.H2Info h2 = s.ensureH2Gateway();
        try (Connection c = DriverManager.getConnection(h2.dbpUrl() + "?apiKey=" + Stack.H2_API_KEY)) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            assertThat(Sql.queryLong(c, "SELECT COUNT(*) FROM customer")).isEqualTo(2);
            assertThat(Sql.queryString(c, "SELECT TO_CHAR(SYSDATE, 'YYYY') FROM DUAL")).as("Oracle compatibility mode (SYSDATE, DUAL)").hasSize(4);
            assertThat(Sql.queryString(c, "SELECT email FROM customer WHERE ROWNUM <= 1 ORDER BY id")).isEqualTo("h2-1@example.org");
            Results.note("second engine: %s %s via jdbc:dbp (static YAML datasource %s, gateway %s)", c.getMetaData().getDatabaseProductName(),
                    c.getMetaData().getDatabaseProductVersion(), Stack.H2_DATASOURCE, Stack.H2_GATEWAY_ID);
        }
        assertThatThrownBy(() -> DriverManager.getConnection(h2.dbpUrl() + "?apiKey=dbp_wrong_key"))
                .isInstanceOf(SQLException.class).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08004"));
        try (Connection pg = s.connect(DS_SALES, ORDERS)) {
            assertThat(pg.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        // the control plane models H2 as well (Engine H2 / OTHER): the H2 server becomes a database, a datasource routes to it and it resolves
        // to jdbc:h2:tcp://host:port/serviceName; the proxy configuration (PostgreSQL / Oracle / SQL Server only) leaves it out
        String dbName = "sales-h2-cp";
        String dsName = "sales-h2-cp";
        JsonNode h2Db = s.cp().post("/databases", Map.of("name", dbName, "engine", "H2", "host", "127.0.0.1", "port", h2.h2TcpPort(), "serviceName", "mem:salesh2",
                "collector", Map.of("enabled", false))).expect(201).json();
        assertThat(h2Db.path("engine").asText()).isEqualTo("H2");
        JsonNode h2Ds = s.cp().post("/datasources", Map.of("name", dsName, "ownerTeamId", s.id("team", "sales-platform"), "state", "ACTIVE",
                "currentDatabaseId", h2Db.path("id").asText(), "poolPolicy", Map.of("mode", "TRANSACTION", "maxConnections", 2, "minIdle", 0))).expect(201).json();
        JsonNode h2Grant = s.cp().post("/access-grants", Map.of("applicationId", s.id("app", BATCH), "datasourceId", h2Ds.path("id").asText(), "maxLogicalConnections", 2)).expect(201).json();
        try {
            JsonNode resolved = s.cp().internalGet("/internal/resolve/datasource/" + dsName + "?applicationId=" + s.id("app", BATCH)).json();
            assertThat(resolved.path("database").path("engine").asText()).isEqualTo("H2");
            assertThat(resolved.path("database").path("jdbcUrl").asText()).isEqualTo("jdbc:h2:tcp://127.0.0.1:" + h2.h2TcpPort() + "/mem:salesh2");
            JsonNode proxyCfg = s.cp().internalGet("/internal/proxy/config?proxyId=" + Stack.PROXY_ID).json();
            assertThat(stream(proxyCfg.path("listeners")).map(l -> l.path("engine").asText())).as("no proxy listener for H2").doesNotContain("H2", "OTHER");
            assertThat(stream(proxyCfg.path("listeners")).flatMap(l -> stream(l.path("routes"))).map(r -> r.path("match").asText())).as("no proxy route for the H2 datasource").doesNotContain(dsName);
            Results.note("control plane models H2: POST /databases engine H2 -> 201, /internal/resolve -> %s, left out of /internal/proxy/config", resolved.path("database").path("jdbcUrl").asText());
        } finally {
            // clean up so the later scenarios see the configuration they expect
            s.cp().delete("/access-grants/" + h2Grant.path("id").asText());
            s.cp().delete("/datasources/" + h2Ds.path("id").asText());
            s.cp().delete("/databases/" + h2Db.path("id").asText());
        }
    }
}
