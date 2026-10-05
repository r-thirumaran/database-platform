package org.dbplatform.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.dbplatform.it.support.Await;
import org.dbplatform.it.support.ControlPlaneApi;
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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.it.support.ControlPlaneApi.items;
import static org.dbplatform.it.support.ControlPlaneApi.stream;
import static org.dbplatform.it.support.Stack.DB_PG;
import static org.dbplatform.it.support.Stack.DS_SALES;
import static org.dbplatform.it.support.Stack.ORDERS;

/** Scenario 6: gateway telemetry -> control plane statistics, catalogue, CALL expansion, impact and graph. */
@ExtendWith(ItExtension.class)
@Order(6)
@DisplayName("6 Telemetry round trip")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S06TelemetryIT {

    private static String tableId(Stack s, String name) {
        return Await.until("table sales." + name + " in the catalogue", Duration.ofSeconds(30), () -> {
            List<JsonNode> hits = items(s.cp().get("/tables?databaseId=" + s.id("db", DB_PG) + "&schema=sales&size=500").json())
                    .filter(t -> name.equalsIgnoreCase(t.path("name").asText())).toList();
            // prefer the crawled row should the catalogue hold a discovered duplicate
            return hits.stream().filter(t -> !t.path("discovered").asBoolean()).findFirst().or(() -> hits.stream().findFirst()).map(t -> t.path("id").asText());
        });
    }

    private static Optional<JsonNode> routine(Stack s, String name) {
        return items(s.cp().get("/routines?databaseId=" + s.id("db", DB_PG) + "&schema=sales&size=500").json())
                .filter(r -> name.equalsIgnoreCase(r.path("name").asText())).findFirst();
    }

    @Test
    @Order(1)
    void top_queries_show_normalised_statements_with_their_tables() {
        Stack s = Stack.current();
        s.ensureGateway();
        String ordersId = s.id("app", ORDERS);
        List<JsonNode> top = Await.until("GET /stats/queries/top has orders-service statements on customer", Duration.ofSeconds(60), () -> {
            List<JsonNode> list = items(s.cp().get("/stats/queries/top?by=count&window=24h&applicationId=" + ordersId + "&limit=200").json()).toList();
            boolean customerSelect = list.stream().anyMatch(q -> "SELECT".equals(q.path("operation").asText())
                    && stream(q.path("tables")).anyMatch(t -> "customer".equalsIgnoreCase(t.path("name").asText())));
            boolean call = list.stream().anyMatch(q -> "CALL".equals(q.path("operation").asText()));
            return customerSelect && call ? Optional.of(list) : Optional.empty();
        });
        JsonNode sample = top.stream().filter(q -> "SELECT".equals(q.path("operation").asText()) && stream(q.path("tables")).anyMatch(t -> "customer".equalsIgnoreCase(t.path("name").asText()))).findFirst().orElseThrow();
        assertThat(sample.path("sqlNormalized").asText()).containsIgnoringCase("customer").contains("?");
        assertThat(sample.path("sqlHash").asText()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(sample.path("count").asLong()).isPositive();
        assertThat(sample.path("applicationName").asText()).isEqualTo(ORDERS);
        assertThat(top.stream().allMatch(q -> q.path("sqlNormalized").asText().isEmpty() || !q.path("sqlNormalized").asText().matches(".*'[^']*@example\\.org'.*")))
                .as("literals are replaced by ? (no e-mail literals leak into normalised SQL)").isTrue();
        JsonNode call = top.stream().filter(q -> "CALL".equals(q.path("operation").asText())).findFirst().orElseThrow();
        Results.note("%d distinct statements for orders-service; e.g. %s (count %d, tables %s); CALL: %s", top.size(),
                sample.path("sqlNormalized").asText().replaceAll("\\s+", " "), sample.path("count").asLong(),
                stream(sample.path("tables")).map(t -> t.path("name").asText()).toList(), call.path("sqlNormalized").asText());
    }

    @Test
    @Order(2)
    void tables_endpoint_lists_the_sales_schema() {
        Stack s = Stack.current();
        JsonNode tables = Await.until("GET /tables?schema=sales contains customer and orders", Duration.ofSeconds(30), () -> {
            JsonNode t = s.cp().get("/tables?databaseId=" + s.id("db", DB_PG) + "&schema=sales&size=500").json();
            List<String> names = items(t).map(n -> n.path("name").asText().toLowerCase()).toList();
            return names.contains("customer") && names.contains("orders") ? Optional.of(t) : Optional.empty();
        });
        Map<String, Boolean> discovered = items(tables).collect(Collectors.toMap(n -> n.path("name").asText(), n -> n.path("discovered").asBoolean(), (a, b) -> a));
        assertThat(items(tables).allMatch(n -> "sales".equals(n.path("schema").asText()))).isTrue();
        Results.note("sales tables known to the catalogue: %s", discovered);
    }

    @Test
    @Order(3)
    void application_summary_shows_reads_on_customer_and_orders_and_calls_on_place_order() {
        Stack s = Stack.current();
        JsonNode summary = Await.until("application summary with READS customer/orders and CALLS order_pkg_place_order", Duration.ofSeconds(60), () -> {
            JsonNode sm = s.cp().get("/applications/" + s.id("app", ORDERS) + "/summary").json();
            List<String> reads = stream(sm.path("reads")).map(t -> t.path("name").asText().toLowerCase()).toList();
            List<String> calls = stream(sm.path("calls")).map(t -> t.path("name").asText().toLowerCase()).toList();
            return reads.contains("customer") && reads.contains("orders") && calls.contains("order_pkg_place_order") ? Optional.of(sm) : Optional.empty();
        });
        assertThat(summary.path("application").path("name").asText()).isEqualTo(ORDERS);
        assertThat(summary.path("team").path("name").asText()).isEqualTo("sales-platform");
        assertThat(summary.path("queryStats").path("count24h").asLong()).isPositive();
        assertThat(stream(summary.path("writes")).map(t -> t.path("name").asText().toLowerCase())).contains("customer");
        Results.note("reads=%s writes=%s calls=%s queryStats=%s", stream(summary.path("reads")).map(t -> t.path("name").asText()).toList(),
                stream(summary.path("writes")).map(t -> t.path("name").asText()).toList(), stream(summary.path("calls")).map(t -> t.path("name").asText()).toList(),
                summary.path("queryStats"));
    }

    @Test
    @Order(4)
    void dictionary_crawl_catalogues_routines_triggers_and_dependencies() throws Exception {
        Stack s = Stack.current();
        String dbId = s.id("db", DB_PG);
        // 1. the crawl that ran at import time used the grants shipped in demo/sql/postgres/40_grants_app.sql (dbp_collector: pg_monitor + SELECT on audit_log only)
        JsonNode shipped = Await.until("first dictionary crawl (shipped collector grants) finished", Duration.ofSeconds(90), () -> {
            JsonNode st = s.cp().get("/databases/" + dbId + "/collector-status").json();
            return st.path("lastDictionaryRun").isTextual() ? Optional.of(st) : Optional.empty();
        });
        List<JsonNode> tablesBefore = items(s.cp().get("/tables?databaseId=" + dbId + "&schema=sales&size=500").json()).toList();
        long crawledBefore = tablesBefore.stream().filter(t -> !t.path("discovered").asBoolean()).count();
        Results.note("crawl with the shipped grants: collector-status.tablesSeen=%s routinesSeen=%s, %d of %d sales tables catalogued (not 'discovered'): %s",
                shipped.path("tablesSeen").asText(), shipped.path("routinesSeen").asText(), crawledBefore, tablesBefore.size(),
                tablesBefore.stream().filter(t -> !t.path("discovered").asBoolean()).map(t -> t.path("name").asText()).toList());
        if (crawledBefore < 8) {
            // workaround (test only, demo scripts untouched): information_schema.tables/columns are privilege filtered in PostgreSQL
            try (Connection su = s.pgSuperuser("sales")) {
                Sql.exec(su, "GRANT SELECT ON ALL TABLES IN SCHEMA sales TO dbp_collector");
            }
            Results.partial("PostgresDictionaryCrawler reads information_schema.tables/columns, which only list objects the collector role has privileges on;"
                    + " with the shipped grants only " + crawledBefore + " of 8 tables were catalogued (view typed as TABLE, routine->table dependencies missing)."
                    + " Workaround applied by the test: GRANT SELECT ON ALL TABLES IN SCHEMA sales TO dbp_collector, then re-crawl");
        }
        JsonNode started = s.cp().post("/databases/" + dbId + "/collect", Map.of("what", "DICTIONARY")).json();
        assertThat(started.has("started")).isTrue();
        String firstRun = shipped.path("lastDictionaryRun").asText();
        JsonNode status = Await.until("dictionary crawl with full visibility finished", Duration.ofSeconds(120), () -> {
            JsonNode st = s.cp().get("/databases/" + dbId + "/collector-status").json();
            if (!st.path("lastDictionaryRun").isTextual() || st.path("lastDictionaryRun").asText().equals(firstRun) || st.path("tablesSeen").asInt() < 8) {
                return Optional.empty();
            }
            Optional<JsonNode> place = routine(s, "order_pkg_place_order");
            return place.isPresent() && !place.get().path("discovered").asBoolean() ? Optional.of(st) : Optional.empty();
        });
        JsonNode placeOrder = routine(s, "order_pkg_place_order").orElseThrow();
        assertThat(placeOrder.path("kind").asText()).isEqualTo("PROCEDURE");
        assertThat(routine(s, "get_customer_tier").map(r -> r.path("kind").asText())).contains("FUNCTION");
        JsonNode trigger = routine(s, "trg_orders_audit").orElseThrow();
        assertThat(trigger.path("kind").asText()).isEqualTo("TRIGGER");
        assertThat(trigger.path("triggerTableId").asText()).isEqualTo(tableId(s, "orders"));
        JsonNode deps = s.cp().get("/dependencies?fromId=" + placeOrder.path("id").asText()).json();
        List<String> depKinds = items(deps).map(d -> d.path("kind").asText()).distinct().toList();
        assertThat(items(deps).count()).as("order_pkg_place_order dependencies").isGreaterThanOrEqualTo(3);
        assertThat(depKinds).contains("WRITES");
        JsonNode rs = s.cp().get("/routines/" + placeOrder.path("id").asText() + "/summary").json();
        List<String> tables = stream(rs.path("tables")).map(t -> t.path("name").asText().toLowerCase()).toList();
        assertThat(tables).contains("orders", "order_item", "payment");
        JsonNode schemas = s.cp().get("/databases/" + dbId + "/schemas").json();
        JsonNode sales = stream(schemas).filter(n -> "sales".equals(n.path("name").asText())).findFirst().orElseThrow();
        assertThat(sales.path("tableCount").asInt()).isGreaterThanOrEqualTo(8);
        assertThat(sales.path("routineCount").asInt()).isGreaterThanOrEqualTo(8);
        JsonNode customer = s.cp().get("/tables/" + tableId(s, "customer")).json();
        assertThat(customer.path("discovered").asBoolean()).as("crawl replaces the discovered placeholder").isFalse();
        JsonNode view = s.cp().get("/tables/" + tableId(s, "v_customer_order_summary")).json();
        assertThat(view.path("kind").asText()).as("views are catalogued as kind VIEW").isEqualTo("VIEW");
        long duplicates = items(s.cp().get("/tables?databaseId=" + dbId + "&size=500").json())
                .filter(t -> "sales".equalsIgnoreCase(t.path("schema").asText()) && "orders".equalsIgnoreCase(t.path("name").asText())).count();
        assertThat(duplicates).as("exactly one catalogue row for sales.orders").isEqualTo(1);
        assertThat(customer.path("rowCountEstimate").asLong()).isGreaterThanOrEqualTo(0);
        JsonNode columns = s.cp().get("/tables/" + tableId(s, "customer") + "/columns").json();
        assertThat(stream(columns).map(c -> c.path("name").asText())).contains("email", "country_code");
        Results.note("collector-status: lastError=%s tablesSeen=%s routinesSeen=%s; place_order deps=%d kinds=%s tables=%s; schema sales: %s tables / %s routines%s",
                status.path("lastError").asText(null), status.path("tablesSeen").asText(), status.path("routinesSeen").asText(), items(deps).count(), depKinds, tables,
                sales.path("tableCount").asText(), sales.path("routineCount").asText(), s.pg().statStatementsLoaded() ? "" : " (pg_stat_statements not preloaded)");
    }

    @Test
    @Order(5)
    void call_expansion_attributes_tables_written_via_the_routine_to_the_caller() throws Exception {
        Stack s = Stack.current();
        try (Connection c = s.connect(DS_SALES, ORDERS); CallableStatement cs = c.prepareCall("{call order_pkg_place_order(?, ?, ?, ?)}")) {
            cs.setLong(1, 7);
            cs.setLong(2, 9);
            cs.setInt(3, 1);
            cs.setNull(4, Types.BIGINT);
            cs.registerOutParameter(4, Types.BIGINT);
            cs.execute();
            assertThat(cs.getLong(4)).isPositive();
        }
        String ordersTable = tableId(s, "orders");
        JsonNode consumer = Await.until("orders table summary lists orders-service via order_pkg_place_order", Duration.ofSeconds(60), () ->
                stream(s.cp().get("/tables/" + ordersTable + "/summary").json().path("consumers"))
                        .filter(e -> ORDERS.equals(e.path("application").path("name").asText()) && !e.path("viaRoutine").isNull() && e.path("viaRoutine").isObject())
                        .findFirst());
        assertThat(consumer.path("viaRoutine").path("name").asText()).isEqualToIgnoringCase("order_pkg_place_order");
        assertThat(consumer.path("kind").asText()).isEqualTo("WRITES");
        assertThat(consumer.path("source").asText()).isEqualTo("GATEWAY");
        JsonNode rels = s.cp().get("/relationships?applicationId=" + s.id("app", ORDERS) + "&objectId=" + ordersTable).json();
        assertThat(stream(rels).anyMatch(r -> !r.path("viaRoutineId").isNull() && "WRITES".equals(r.path("kind").asText()))).isTrue();
        // tables the application never named in its own SQL but which the routine (and its triggers) touch
        for (String t : List.of("payment", "order_item", "audit_log")) {
            String id = tableId(s, t);
            boolean via = stream(s.cp().get("/tables/" + id + "/summary").json().path("consumers"))
                    .anyMatch(e -> ORDERS.equals(e.path("application").path("name").asText()) && e.path("viaRoutine").isObject());
            assertThat(via).as("orders-service is an indirect consumer of " + t).isTrue();
        }
        Results.note("orders.summary consumer: %s %s via %s (confidence %s); indirect consumers also on payment, order_item, audit_log (trigger)", ORDERS,
                consumer.path("kind").asText(), consumer.path("viaRoutine").path("name").asText(), consumer.path("confidence").asText());
    }

    @Test
    @Order(6)
    void impact_analysis_and_graph_include_the_consumers() {
        Stack s = Stack.current();
        String ordersTable = tableId(s, "orders");
        JsonNode impact = s.cp().get("/impact/table/" + ordersTable).json();
        assertThat(impact.path("target").path("label").asText()).containsIgnoringCase("orders");
        assertThat(impact.path("riskScore").isNumber()).isTrue();
        assertThat(impact.path("riskScore").asDouble()).isBetween(0.0, 1.0);
        assertThat(stream(impact.path("directConsumers")).map(c -> c.path("application").path("name").asText())).contains(ORDERS);
        assertThat(stream(impact.path("indirectConsumers")).map(c -> c.path("application").path("name").asText())).contains(ORDERS);
        assertThat(stream(impact.path("triggers")).map(t -> t.path("name").asText().toLowerCase())).contains("trg_orders_audit");
        assertThat(stream(impact.path("dependentViews")).map(t -> t.path("name").asText().toLowerCase())).contains("v_customer_order_summary");
        assertThat(impact.path("owner").path("name").asText()).isEqualTo("sales-platform");

        JsonNode graph = s.cp().get("/graph?root=application:" + s.id("app", ORDERS) + "&depth=2").json();
        List<String> nodeTypes = stream(graph.path("nodes")).map(n -> n.path("type").asText()).distinct().toList();
        assertThat(nodeTypes).contains("APPLICATION", "TABLE");
        assertThat(stream(graph.path("nodes")).anyMatch(n -> n.path("id").asText().equals("table:" + ordersTable))).isTrue();
        List<String> edgeKinds = stream(graph.path("edges")).map(e -> e.path("kind").asText()).distinct().toList();
        assertThat(edgeKinds).contains("READS");
        assertThat(edgeKinds).containsAnyOf("CALLS", "WRITES");
        Results.note("impact(orders): riskScore=%.2f factors=%s direct=%d indirect=%d; graph(application, depth 2): %d nodes %s, edges %s",
                impact.path("riskScore").asDouble(), stream(impact.path("riskFactors")).map(JsonNode::asText).toList(),
                stream(impact.path("directConsumers")).count(), stream(impact.path("indirectConsumers")).count(), stream(graph.path("nodes")).count(), nodeTypes, edgeKinds);
        ControlPlaneApi.Response governance = s.cp().post("/governance/evaluate", null);
        assertThat(governance.status()).isEqualTo(200);
        Results.note("governance evaluate: %s; open violations: %d", governance.body(), items(s.cp().get("/governance/violations?status=OPEN").json()).count());
    }
}
