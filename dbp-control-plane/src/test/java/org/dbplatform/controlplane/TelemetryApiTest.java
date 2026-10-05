package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.dbplatform.common.telemetry.ConnectionEvent;
import org.dbplatform.common.telemetry.ConnectionEventType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.IdentitySource;
import org.dbplatform.common.telemetry.PoolStats;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.junit.jupiter.api.Test;

/** Telemetry ingestion: relationships, query stats, CALL expansion, idempotency, connections, pools, heartbeats. */
class TelemetryApiTest extends AbstractApiTest {

    @Test
    void queryEventsDeriveRelationshipsStatsAndExpandCalls() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        String appId = application(uniq("orders"), teamId, "SERVICE", Map.of()).get("id").asText();
        String appName = getJson("/api/v1/applications/" + appId, 200).get("name").asText();
        String credId = credential(uniq("cred"), "INLINE", null, "pw").get("id").asText();
        String dbId = database(uniq("ora"), "ORACLE", "oracle", 1521, "FREEPDB1", credId, List.of("SALES")).get("id").asText();
        String dsName = uniq("sales");
        String dsId = datasource(dsName, teamId, dbId, null, null).get("id").asText();
        grant(appId, dsId, Map.of());

        // catalogue: CUSTOMER table, ORDERS table (via import of ownership is not needed), routine PLACE_ORDER with dictionary dependencies
        // tables are created through the catalogue by discovery of a first event (placeholders) – seed two real ones through import
        JsonNode imported = postJson("/api/v1/import", Map.of("teams", List.of(), "ownership", List.of()), 200);
        assertThat(imported.get("ok").asBoolean()).isTrue();
        assertThat(imported.get("imported").get("teams").asInt()).isZero();
        // discover tables by telemetry first
        QueryEvent e1 = QueryEvent.builder().eventId(uniq("evt")).timestamp(Instant.now()).gatewayId("gw-1").sessionId("s-1").applicationId(appId).application(appName)
                .datasource(dsName).databaseId(dbId).engine(Engine.ORACLE).sqlHash("h1").sqlNormalized("SELECT * FROM sales.customer WHERE id = ?").operation(SqlOperation.SELECT)
                .tables(List.of(TableAccess.read("SALES", "CUSTOMER"))).durationMs(12).rows(1).build();
        JsonNode acc = internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(e1)), 202);
        assertThat(acc.get("accepted").asInt()).isEqualTo(1);
        // idempotent on eventId
        assertThat(internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(e1)), 202).get("accepted").asInt()).isZero();

        JsonNode tables = getJson("/api/v1/tables?databaseId=" + dbId + "&schema=SALES", 200);
        assertThat(tables).hasSize(1);
        JsonNode customer = tables.get(0);
        assertThat(customer.get("name").asText()).isEqualTo("CUSTOMER");
        assertThat(customer.get("discovered").asBoolean()).isTrue();
        assertThat(customer.get("ownerSource").asText()).isEqualTo("NONE");
        String customerId = customer.get("id").asText();
        JsonNode rels = getJson("/api/v1/relationships?applicationId=" + appId + "&objectId=" + customerId, 200);
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).get("kind").asText()).isEqualTo("READS");
        assertThat(rels.get(0).get("source").asText()).isEqualTo("GATEWAY");
        assertThat(rels.get(0).get("queryCount").asLong()).isEqualTo(1);
        assertThat(rels.get(0).get("confidence").asDouble()).isEqualTo(1.0);

        // unqualified table name resolved through defaultSchema; a write
        QueryEvent e2 = QueryEvent.builder().eventId(uniq("evt")).applicationId(appId).application(appName).datasource(dsName).databaseId(dbId).engine(Engine.ORACLE)
                .sqlHash("h2").sqlNormalized("UPDATE customer SET tier = ? WHERE id = ?").operation(SqlOperation.UPDATE).defaultSchema("SALES")
                .tables(List.of(TableAccess.write(null, "CUSTOMER"))).durationMs(30).rows(1).build();
        internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(e2, e2)), 202);
        assertThat(getJson("/api/v1/tables?databaseId=" + dbId, 200)).hasSize(1); // no second placeholder
        assertThat(getJson("/api/v1/relationships?applicationId=" + appId + "&objectId=" + customerId + "&kind=WRITES", 200)).hasSize(1);

        // CALL expansion: routine with dictionary dependencies → READS/WRITES via routine
        JsonNode ordersTbl = discover(appId, appName, dsName, dbId, "SALES", "ORDERS");
        JsonNode auditTbl = discover(appId, appName, dsName, dbId, "SALES", "AUDIT_LOG");
        // create the routine by calling it once (discovered placeholder), then declare dependencies
        QueryEvent call0 = QueryEvent.builder().eventId(uniq("evt")).applicationId(appId).application(appName).datasource(dsName).databaseId(dbId).engine(Engine.ORACLE)
                .sqlHash("h3").sqlNormalized("BEGIN sales.order_pkg.place_order(?, ?); END;").operation(SqlOperation.CALL).defaultSchema("SALES")
                .routines(List.of(new RoutineRef("SALES", "ORDER_PKG.PLACE_ORDER"))).durationMs(40).rows(-1).build();
        internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(call0)), 202);
        JsonNode routines = getJson("/api/v1/routines?databaseId=" + dbId + "&q=PLACE_ORDER", 200);
        assertThat(routines).hasSize(1);
        String routineId = routines.get(0).get("id").asText();
        assertThat(routines.get(0).get("name").asText()).isEqualTo("ORDER_PKG.PLACE_ORDER");
        // trigger on ORDERS writing AUDIT_LOG, declared through the dependency API (DECLARED source)
        JsonNode trg = discoverRoutine(appId, appName, dsName, dbId, "SALES", "TRG_ORDERS_AUDIT");
        postJson("/api/v1/dependencies", Map.of("fromType", "ROUTINE", "fromId", routineId, "toType", "TABLE", "toId", ordersTbl.get("id").asText(), "kind", "WRITES"), 201);
        postJson("/api/v1/dependencies", Map.of("fromType", "ROUTINE", "fromId", routineId, "toType", "TABLE", "toId", customerId, "kind", "READS"), 201);
        postJson("/api/v1/dependencies", Map.of("fromType", "TABLE", "fromId", ordersTbl.get("id").asText(), "toType", "ROUTINE", "toId", trg.get("id").asText(), "kind", "TRIGGERS"), 201);
        postJson("/api/v1/dependencies", Map.of("fromType", "ROUTINE", "fromId", trg.get("id").asText(), "toType", "TABLE", "toId", auditTbl.get("id").asText(), "kind", "WRITES"), 201);
        QueryEvent call = QueryEvent.builder().eventId(uniq("evt")).applicationId(appId).application(appName).datasource(dsName).databaseId(dbId).engine(Engine.ORACLE)
                .sqlHash("h3").sqlNormalized("BEGIN sales.order_pkg.place_order(?, ?); END;").operation(SqlOperation.CALL).defaultSchema("SALES")
                .routines(List.of(new RoutineRef("SALES", "ORDER_PKG.PLACE_ORDER"))).durationMs(40).rows(-1).build();
        internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(call)), 202);
        JsonNode calls = getJson("/api/v1/relationships?applicationId=" + appId + "&objectId=" + routineId, 200);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).get("kind").asText()).isEqualTo("CALLS");
        assertThat(calls.get(0).get("queryCount").asLong()).isEqualTo(2);
        JsonNode viaOrders = getJson("/api/v1/relationships?applicationId=" + appId + "&objectId=" + ordersTbl.get("id").asText() + "&kind=WRITES", 200);
        assertThat(viaOrders).hasSize(1);
        assertThat(viaOrders.get(0).get("kind").asText()).isEqualTo("WRITES");
        assertThat(viaOrders.get(0).get("viaRoutineId").asText()).isEqualTo(routineId);
        JsonNode viaAudit = getJson("/api/v1/relationships?applicationId=" + appId + "&objectId=" + auditTbl.get("id").asText() + "&kind=WRITES", 200);
        assertThat(viaAudit).hasSize(1); // through the trigger fired by the written table
        assertThat(viaAudit.get(0).get("viaRoutineId").asText()).isEqualTo(routineId);
        JsonNode viaCustomer = getJson("/api/v1/relationships?applicationId=" + appId + "&objectId=" + customerId, 200);
        assertThat(viaCustomer).hasSize(3); // direct READS, direct WRITES, READS via routine

        // query stats
        JsonNode top = getJson("/api/v1/stats/queries/top?by=count&window=24h&databaseId=" + dbId, 200);
        assertThat(top.size()).isGreaterThanOrEqualTo(3);
        JsonNode h2 = null;
        for (JsonNode q : top) if (q.get("sqlHash").asText().equals("h2")) h2 = q;
        assertThat(h2).isNotNull();
        assertThat(h2.get("count").asLong()).isEqualTo(1); // the duplicated e2 counted once (idempotent)
        assertThat(h2.get("applicationName").asText()).isEqualTo(appName);
        assertThat(h2.get("avgDurationMs").asDouble()).isEqualTo(30.0);
        assertThat(h2.get("tables").get(0).get("name").asText()).isEqualTo("CUSTOMER");
        JsonNode hot = getJson("/api/v1/stats/tables/hot?window=24h", 200);
        assertThat(hot.toString()).contains(customerId);
        JsonNode summary = getJson("/api/v1/tables/" + customerId + "/summary", 200);
        assertThat(summary.get("consumers").size()).isGreaterThanOrEqualTo(2);
        assertThat(summary.get("queryStats").get("count24h").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(summary.get("routines").get(0).get("id").asText()).isEqualTo(routineId);
        JsonNode appSummary = getJson("/api/v1/applications/" + appId + "/summary", 200);
        assertThat(appSummary.get("calls").get(0).get("id").asText()).isEqualTo(routineId);
        assertThat(appSummary.get("writes").toString()).contains(ordersTbl.get("id").asText());
        JsonNode rsum = getJson("/api/v1/routines/" + routineId + "/summary", 200);
        assertThat(rsum.get("callers").get(0).get("application").get("id").asText()).isEqualTo(appId);
        assertThat(rsum.get("callers").get(0).get("kind").asText()).isEqualTo("CALLS");
        assertThat(rsum.get("callers").get(0).get("source").asText()).isEqualTo("GATEWAY");
        assertThat(rsum.get("callers").get(0).get("confidence").asDouble()).isEqualTo(1.0);
        assertThat(rsum.get("tables").size()).isEqualTo(3);
        assertThat(rsum.get("dependencies")).hasSize(2);
        assertThat(rsum.get("dependencies").get(0).get("fromName").asText()).isEqualTo("SALES.ORDER_PKG.PLACE_ORDER");
        assertThat(rsum.get("referencedBy")).isEmpty();
        assertThat(summary.get("queryStats").has("avgDurationMs") && summary.get("queryStats").has("errors24h")).isTrue();
        // DECLARED-only delete rule for dependencies
        JsonNode deps = getJson("/api/v1/dependencies?fromId=" + routineId, 200);
        assertThat(deps).hasSize(2);
        deleteJson("/api/v1/dependencies/" + deps.get(0).get("id").asText(), 204);
        // relationships curation
        JsonNode declared = postJson("/api/v1/relationships", Map.of("applicationId", appId, "objectType", "TABLE", "objectId", customerId, "kind", "READS"), 201);
        assertThat(declared.get("source").asText()).isEqualTo("DECLARED");
        assertThat(declared.get("confirmed").asBoolean()).isTrue();
        JsonNode confirmed = putJson("/api/v1/relationships/" + rels.get(0).get("id").asText(), Map.of("confirmed", true), 200);
        assertThat(confirmed.get("confirmed").asBoolean()).isTrue();
        deleteJson("/api/v1/relationships/" + declared.get("id").asText(), 204);
    }

    private JsonNode discover(String appId, String appName, String dsName, String dbId, String schema, String table) throws Exception {
        QueryEvent e = QueryEvent.builder().eventId(uniq("evt")).applicationId(appId).application(appName).datasource(dsName).databaseId(dbId).engine(Engine.ORACLE)
                .sqlHash(uniq("h")).sqlNormalized("SELECT 1 FROM " + schema + "." + table).operation(SqlOperation.SELECT).tables(List.of(TableAccess.read(schema, table))).durationMs(1).rows(1).build();
        internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(e)), 202);
        JsonNode list = getJson("/api/v1/tables?databaseId=" + dbId + "&q=" + table, 200);
        for (JsonNode t : list) if (t.get("name").asText().equals(table)) return t;
        throw new AssertionError("table not discovered: " + table);
    }

    private JsonNode discoverRoutine(String appId, String appName, String dsName, String dbId, String schema, String name) throws Exception {
        QueryEvent e = QueryEvent.builder().eventId(uniq("evt")).applicationId(appId).application(appName).datasource(dsName).databaseId(dbId).engine(Engine.ORACLE)
                .sqlHash(uniq("h")).sqlNormalized("BEGIN " + name + "; END;").operation(SqlOperation.CALL).routines(List.of(new RoutineRef(schema, name))).durationMs(1).rows(-1).build();
        internalPost("/api/v1/internal/telemetry/queries", TelemetryJson.toJson(List.of(e)), 202);
        JsonNode list = getJson("/api/v1/routines?databaseId=" + dbId + "&q=" + name, 200);
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Test
    void connectionsPoolsAndHeartbeats() throws Exception {
        String teamId = team(uniq("team")).get("id").asText();
        String appId = application(uniq("portal"), teamId, "UI", Map.of()).get("id").asText();
        String dbId = database(uniq("ora"), "ORACLE", "oracle-hb", 1521, "FREEPDB1", null, List.of("SALES")).get("id").asText();
        String dsName = uniq("sales");
        String dsId = datasource(dsName, teamId, dbId, null, null).get("id").asText();
        ConnectionEvent open = ConnectionEvent.builder().eventId(uniq("c")).proxyId("proxy-1").eventType(ConnectionEventType.OPEN).listener("oracle-main").engine(Engine.ORACLE)
                .connectionId("c-1").clientAddr("10.20.3.4").clientPort(51234).proxyLocalAddr("10.30.0.9").proxyLocalPort(40321).backendHost("oracle-hb").backendPort(1521)
                .requestedService(dsName + ".portal").resolvedService("FREEPDB1").applicationId(appId).application("portal").identitySource(IdentitySource.SERVICE_ALIAS)
                .datasourceId(dsId).datasource(dsName).program("JDBC Thin Client").clientHost("portal-1").osUser("app").openedAt(Instant.now()).build();
        assertThat(internalPost("/api/v1/internal/telemetry/connections", TelemetryJson.toJson(List.of(open)), 202).get("accepted").asInt()).isEqualTo(1);
        assertThat(internalPost("/api/v1/internal/telemetry/connections", TelemetryJson.toJson(List.of(open)), 202).get("accepted").asInt()).isZero();

        // heartbeat with a live connection snapshot
        Map<String, Object> hb = Map.of("componentType", "PROXY", "componentId", "proxy-1", "version", "0.1.0", "host", "proxy-1.internal", "startedAt", Instant.now().toString(),
                "configVersion", 3, "stats", Map.of("physicalConnections", 1, "eventsDropped", 0, "liveConnections", List.of(TelemetryJson.mapper().convertValue(open, Map.class))));
        JsonNode hbr = internalPost("/api/v1/internal/heartbeat", hb, 200);
        assertThat(hbr.get("configVersion").asLong()).isPositive();
        JsonNode components = getJson("/api/v1/components", 200);
        JsonNode proxy = null;
        for (JsonNode c : components) if (c.get("componentId").asText().equals("proxy-1")) proxy = c;
        assertThat(proxy).isNotNull();
        assertThat(proxy.get("healthy").asBoolean()).isTrue();
        assertThat(proxy.get("componentType").asText()).isEqualTo("PROXY");
        assertThat(proxy.get("stats").get("liveConnections").asInt()).isEqualTo(1);
        JsonNode live = getJson("/api/v1/connections/live", 200);
        JsonNode row = null;
        for (JsonNode r : live) if ("PROXY".equals(r.get("source").asText()) && dsName.equals(r.path("datasource").asText())) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("program").asText()).isEqualTo("JDBC Thin Client");
        assertThat(row.get("engine").asText()).isEqualTo("ORACLE");
        assertThat(row.get("team").asText()).isNotBlank();
        // gateway heartbeat + pool stats
        internalPost("/api/v1/internal/heartbeat", Map.of("componentType", "GATEWAY", "componentId", "gw-1", "version", "0.1.0", "host", "gw-1", "startedAt", Instant.now().toString(),
                "stats", Map.of("logicalSessions", 180, "physicalConnections", 61, "eventsDropped", 0)), 200);
        PoolStats ps = new PoolStats(Instant.now(), "gw-1", dsName, dsId, dbId, Engine.ORACLE, 12, 4, 0, 16, 40, 180, 12, 3);
        assertThat(internalPost("/api/v1/internal/telemetry/pools", TelemetryJson.toJson(List.of(ps)), 202).get("accepted").asInt()).isEqualTo(1);
        JsonNode pools = getJson("/api/v1/stats/pools", 200);
        JsonNode pool = null;
        for (JsonNode p : pools) if (dsName.equals(p.get("datasourceName").asText())) pool = p;
        assertThat(pool).isNotNull();
        assertThat(pool.get("total").asInt()).isEqualTo(16);
        assertThat(pool.get("logicalSessions").asInt()).isEqualTo(180);
        JsonNode overview = getJson("/api/v1/stats/overview", 200);
        assertThat(overview.get("connections").get("proxyActive").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(overview.get("connections").get("gatewayLogical").asInt()).isGreaterThanOrEqualTo(180);
        assertThat(overview.get("componentsOnline").toString()).contains("gw-1");
        JsonNode grouped = getJson("/api/v1/stats/connections?groupBy=datasource", 200);
        assertThat(grouped.toString()).contains(dsName);
        JsonNode dsSummary = getJson("/api/v1/datasources/" + dsId + "/summary", 200);
        assertThat(dsSummary.get("pools")).hasSize(1);
        // closing the connection removes it from the correlation index; the snapshot still comes from the last heartbeat
        ConnectionEvent close = open.toBuilder().eventId(uniq("c")).eventType(ConnectionEventType.CLOSE).closedAt(Instant.now()).durationMs(1000).build();
        internalPost("/api/v1/internal/telemetry/connections", TelemetryJson.toJson(List.of(close)), 202);
        internalPost("/api/v1/internal/heartbeat", Map.of("componentType", "PROXY", "componentId", "proxy-1", "stats", Map.of("liveConnections", List.of())), 200);
        boolean stillThere = false;
        for (JsonNode r : getJson("/api/v1/connections/live", 200)) if (dsName.equals(r.path("datasource").asText())) stillThere = true;
        assertThat(stillThere).isFalse();
        // heartbeat without identity → 400
        internalPost("/api/v1/internal/heartbeat", Map.of("version", "x"), 400);
    }
}
