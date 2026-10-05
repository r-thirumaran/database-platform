package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON round trips for every telemetry record, asserting the exact field names of docs/telemetry-events.md. */
class TelemetryJsonTest {

    private final ObjectMapper mapper = TelemetryJson.mapper();

    @Test
    void queryEventRoundTripUsesSpecFieldNames() throws Exception {
        QueryEvent event = QueryEvent.builder()
                .eventId("01J0000000000000000000001")
                .timestamp(Instant.parse("2026-10-05T12:00:00.123Z"))
                .gatewayId("gw-1").sessionId("s-123").applicationId("app-1").application("orders-service")
                .team("sales-platform").datasource("sales").databaseId("db-1").engine(Engine.ORACLE)
                .sqlHash("9f2b").sqlNormalized("SELECT * FROM sales.customer WHERE id = ?")
                .operation(SqlOperation.SELECT)
                .tables(List.of(new TableAccess("SALES", "CUSTOMER", AccessType.READ)))
                .routines(List.of(new RoutineRef("SALES", "ORDER_PKG.PLACE_ORDER")))
                .columns(List.of("CUSTOMER.EMAIL"))
                .defaultSchema("SALES")
                .durationMs(31).rows(1).success(true).sqlState(null).errorCode(0).errorMessage(null)
                .pinned(false).poolMode("TRANSACTION")
                .clientInfo(Map.of("ApplicationName", "orders", "module", "checkout", "action", "place"))
                .build();

        String json = mapper.writeValueAsString(event);
        JsonNode node = mapper.readTree(json);

        assertThat(fieldNames(node)).containsExactlyInAnyOrder(
                "eventId", "timestamp", "gatewayId", "sessionId", "applicationId", "application", "team",
                "datasource", "databaseId", "engine", "sqlHash", "sqlNormalized", "operation", "tables", "routines",
                "columns", "defaultSchema", "durationMs", "rows", "success", "errorCode", "pinned", "poolMode",
                "clientInfo");
        assertThat(node.get("timestamp").asText()).isEqualTo("2026-10-05T12:00:00.123Z");
        assertThat(node.get("engine").asText()).isEqualTo("ORACLE");
        assertThat(node.get("operation").asText()).isEqualTo("SELECT");
        assertThat(fieldNames(node.get("tables").get(0))).containsExactly("schema", "name", "access");
        assertThat(node.get("tables").get(0).get("access").asText()).isEqualTo("READ");
        assertThat(fieldNames(node.get("routines").get(0))).containsExactly("schema", "name");
        assertThat(node.get("columns").get(0).asText()).isEqualTo("CUSTOMER.EMAIL");
        assertThat(node.get("clientInfo").get("ApplicationName").asText()).isEqualTo("orders");
        // nulls are omitted
        assertThat(node.has("sqlState")).isFalse();
        assertThat(node.has("errorMessage")).isFalse();

        QueryEvent back = mapper.readValue(json, QueryEvent.class);
        assertThat(back).isEqualTo(event);
    }

    @Test
    void queryEventTruncatesErrorMessageAndNormalizedSql() {
        QueryEvent e = QueryEvent.builder().errorMessage("x".repeat(1000)).sqlNormalized("y".repeat(5000)).build();
        assertThat(e.errorMessage()).hasSize(QueryEvent.MAX_ERROR_MESSAGE);
        assertThat(e.sqlNormalized()).hasSize(QueryEvent.MAX_SQL_NORMALIZED);
        assertThat(e.rows()).isEqualTo(-1);
        assertThat(e.tables()).isEmpty();
    }

    @Test
    void queryEventDeserializesSpecExampleWithNullsAndUnknownFields() throws Exception {
        String json = """
                { "eventId": "01J", "timestamp": "2026-10-05T12:00:00.123Z", "gatewayId": "gw-1", "sessionId": "s-123",
                  "applicationId": null, "application": "orders-service", "team": null, "datasource": "sales",
                  "databaseId": null, "engine": "ORACLE", "sqlHash": "9f2b", "sqlNormalized": "SELECT 1",
                  "operation": "SELECT", "tables": [ { "schema": "SALES", "name": "CUSTOMER", "access": "READ" } ],
                  "routines": [ { "schema": "SALES", "name": "ORDER_PKG.PLACE_ORDER" } ], "columns": [ "CUSTOMER.EMAIL" ],
                  "durationMs": 31, "rows": 1, "success": true, "sqlState": null, "errorCode": 0, "errorMessage": null,
                  "pinned": false, "poolMode": "TRANSACTION", "clientInfo": { "ApplicationName": "x" }, "futureField": 1 }
                """;
        QueryEvent e = mapper.readValue(json, QueryEvent.class);
        assertThat(e.applicationId()).isNull();
        assertThat(e.engine()).isEqualTo(Engine.ORACLE);
        assertThat(e.tables()).containsExactly(new TableAccess("SALES", "CUSTOMER", AccessType.READ));
        assertThat(e.routines()).containsExactly(new RoutineRef("SALES", "ORDER_PKG.PLACE_ORDER"));
        assertThat(e.durationMs()).isEqualTo(31);
        assertThat(e.rows()).isEqualTo(1);
        assertThat(e.success()).isTrue();
    }

    @Test
    void unknownEnumValuesFallBackToDefaults() throws Exception {
        String json = "{ \"engine\": \"DB2\", \"operation\": \"WHATEVER\", \"eventType\": \"X\", \"identitySource\": \"Y\" }";
        QueryEvent q = mapper.readValue(json, QueryEvent.class);
        assertThat(q.engine()).isEqualTo(Engine.OTHER);
        assertThat(q.operation()).isEqualTo(SqlOperation.OTHER);
        ConnectionEvent c = mapper.readValue(json, ConnectionEvent.class);
        assertThat(c.eventType()).isEqualTo(ConnectionEventType.BACKEND_FAILED);
        assertThat(c.identitySource()).isEqualTo(IdentitySource.NONE);
        assertThat(mapper.readValue("{\"engine\":\"postgres\"}", PoolStats.class).engine()).isEqualTo(Engine.POSTGRES);
    }

    @Test
    void connectionEventRoundTripUsesSpecFieldNames() throws Exception {
        ConnectionEvent event = ConnectionEvent.builder()
                .eventId("e-1").timestamp(Instant.parse("2026-10-05T12:00:00Z")).proxyId("proxy-1")
                .eventType(ConnectionEventType.OPEN).listener("oracle-main").engine(Engine.ORACLE)
                .connectionId("c-42").clientAddr("10.20.3.4").clientPort(51234).proxyLocalAddr("10.30.0.9")
                .proxyLocalPort(40321).backendHost("oracle").backendPort(1521)
                .requestedService("sales.orders-service").resolvedService("FREEPDB1")
                .applicationId("app-1").application("orders-service").identitySource(IdentitySource.SERVICE_ALIAS)
                .datasourceId("ds-1").datasource("sales").program("JDBC Thin Client").clientHost("orders-7f9c")
                .osUser("app").dbUser(null).openedAt(Instant.parse("2026-10-05T12:00:00Z")).closedAt(null)
                .durationMs(0).bytesIn(0).bytesOut(0).reason(null)
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(event));
        assertThat(fieldNames(node)).containsExactlyInAnyOrder(
                "eventId", "timestamp", "proxyId", "eventType", "listener", "engine", "connectionId", "clientAddr",
                "clientPort", "proxyLocalAddr", "proxyLocalPort", "backendHost", "backendPort", "requestedService",
                "resolvedService", "applicationId", "application", "identitySource", "datasourceId", "datasource",
                "program", "clientHost", "osUser", "openedAt", "durationMs", "bytesIn", "bytesOut");
        assertThat(node.get("eventType").asText()).isEqualTo("OPEN");
        assertThat(node.get("identitySource").asText()).isEqualTo("SERVICE_ALIAS");
        assertThat(node.get("proxyLocalPort").asInt()).isEqualTo(40321);

        ConnectionEvent back = mapper.readValue(node.toString(), ConnectionEvent.class);
        assertThat(back).isEqualTo(event);

        ConnectionEvent closed = event.toBuilder().eventType(ConnectionEventType.CLOSE)
                .closedAt(Instant.parse("2026-10-05T12:05:00Z")).durationMs(300_000).reason("client disconnect").build();
        JsonNode closedNode = mapper.readTree(mapper.writeValueAsString(closed));
        assertThat(closedNode.get("closedAt").asText()).isEqualTo("2026-10-05T12:05:00Z");
        assertThat(closedNode.get("reason").asText()).isEqualTo("client disconnect");
        assertThat(closed.connectionId()).isEqualTo(event.connectionId());
    }

    @Test
    void poolStatsRoundTripUsesSpecFieldNames() throws Exception {
        PoolStats stats = new PoolStats(Instant.parse("2026-10-05T12:00:00Z"), "gw-1", "sales", "ds-1", "db-1",
                Engine.ORACLE, 12, 4, 0, 16, 40, 180, 12, 3);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(stats));
        assertThat(fieldNames(node)).containsExactly(
                "timestamp", "gatewayId", "datasource", "datasourceId", "databaseId", "engine", "active", "idle",
                "waiting", "total", "max", "logicalSessions", "pinnedSessions", "credentialVersion");
        assertThat(node.get("max").asInt()).isEqualTo(40);
        assertThat(mapper.readValue(node.toString(), PoolStats.class)).isEqualTo(stats);
    }

    @Test
    void heartbeatRoundTripUsesSpecFieldNames() throws Exception {
        ConnectionEvent live = ConnectionEvent.builder().eventId("e").proxyId("proxy-1").eventType(ConnectionEventType.OPEN)
                .connectionId("c-1").engine(Engine.POSTGRES).build();
        Heartbeat hb = new Heartbeat(ComponentType.PROXY, "proxy-1", "0.1.0", "proxy-1.internal",
                Instant.parse("2026-10-05T11:00:00Z"), 17L, Heartbeat.Stats.proxy(61, 0, List.of(live)));
        JsonNode node = mapper.readTree(mapper.writeValueAsString(hb));
        assertThat(fieldNames(node)).containsExactly(
                "componentType", "componentId", "version", "host", "startedAt", "configVersion", "stats");
        assertThat(fieldNames(node.get("stats"))).containsExactly(
                "logicalSessions", "physicalConnections", "eventsDropped", "liveConnections");
        assertThat(node.get("stats").get("liveConnections")).hasSize(1);
        assertThat(node.get("stats").get("liveConnections").get(0).get("connectionId").asText()).isEqualTo("c-1");
        assertThat(mapper.readValue(node.toString(), Heartbeat.class)).isEqualTo(hb);

        Heartbeat gateway = new Heartbeat(ComponentType.GATEWAY, "gw-1", "0.1.0", "gw-1", Instant.now(), null,
                Heartbeat.Stats.gateway(180, 61, 0));
        JsonNode g = mapper.readTree(mapper.writeValueAsString(gateway));
        assertThat(g.has("configVersion")).isFalse();
        assertThat(g.get("stats").get("liveConnections")).isEmpty();
        assertThat(mapper.readValue("{\"componentType\":\"GATEWAY\",\"componentId\":\"gw-1\"}", Heartbeat.class).stats())
                .isEqualTo(new Heartbeat.Stats(0, 0, 0, List.of()));
    }

    @Test
    void heartbeatCapsLiveConnections() {
        List<ConnectionEvent> many = java.util.stream.IntStream.range(0, Heartbeat.MAX_LIVE_CONNECTIONS + 10)
                .mapToObj(i -> ConnectionEvent.builder().connectionId("c-" + i).build()).toList();
        Heartbeat.Stats stats = Heartbeat.Stats.proxy(1, 0, many);
        assertThat(stats.liveConnections()).hasSize(Heartbeat.MAX_LIVE_CONNECTIONS);
    }

    @Test
    void helpersSerializeListsAndParseEngines() {
        List<TableAccess> list = List.of(TableAccess.read(null, "ORDERS"), TableAccess.write("SALES", "CUSTOMER"));
        String json = TelemetryJson.toJson(list);
        assertThat(json).isEqualTo("[{\"name\":\"ORDERS\",\"access\":\"READ\"},{\"schema\":\"SALES\",\"name\":\"CUSTOMER\",\"access\":\"WRITE\"}]");
        assertThat(TelemetryJson.listFromJson(json, TableAccess.class)).isEqualTo(list);
        assertThat(TelemetryJson.fromJson("{\"schema\":\"S\",\"name\":\"P.Q\"}", RoutineRef.class).qualifiedName()).isEqualTo("S.P.Q");
        assertThat(Engine.parse("postgresql")).isEqualTo(Engine.POSTGRES);
        assertThat(Engine.parse("SqlServer")).isEqualTo(Engine.MSSQL);
        assertThat(Engine.parse(null)).isEqualTo(Engine.OTHER);
        assertThat(TelemetryJson.newMapper()).isNotSameAs(TelemetryJson.mapper());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
