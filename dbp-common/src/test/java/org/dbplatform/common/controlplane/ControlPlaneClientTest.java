package org.dbplatform.common.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.dbplatform.common.telemetry.ComponentType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.Heartbeat;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneClientTest {

    record Call(String method, String path, String query, String token, JsonNode body) {}

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private ControlPlaneClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("X-DBP-Service-Token"),
                    body.length == 0 ? null : TelemetryJson.mapper().readTree(body)));
            int status = 200;
            String resp;
            switch (path) {
                case "/api/v1/internal/heartbeat" -> resp = "{\"configVersion\": 17}";
                case "/api/v1/internal/config-version" -> resp = "{\"configVersion\": 18}";
                case "/api/v1/internal/auth/application" -> {
                    String key = TelemetryJson.mapper().readTree(body).path("apiKey").asText();
                    if (key.equals("dbp_good")) {
                        resp = "{\"applicationId\":\"app-1\",\"name\":\"orders-service\",\"teamId\":\"team-1\",\"teamName\":\"sales-platform\",\"tags\":[\"tier1\"]}";
                    } else {
                        status = 401;
                        resp = "{\"status\":401,\"error\":\"UNAUTHORIZED\",\"message\":\"unknown api key\",\"path\":\"" + path + "\"}";
                    }
                }
                case "/api/v1/internal/resolve/datasource/sales" -> resp = """
                        { "datasource": { "id": "ds-1", "name": "sales", "state": "ACTIVE" },
                          "grant": { "maxLogicalConnections": 50, "readOnly": false, "poolMode": "TRANSACTION" },
                          "database": { "id": "db-1", "name": "oracle-main", "engine": "ORACLE", "host": "oracle", "port": 1521,
                                        "serviceName": "FREEPDB1", "jdbcUrl": "jdbc:oracle:thin:@//oracle:1521/FREEPDB1",
                                        "jdbcProperties": { "oracle.jdbc.timezoneAsRegion": "false" } },
                          "credential": { "id": "cred-1", "username": "SALES_APP", "version": 3 },
                          "poolPolicy": { "maxSize": 20, "minIdle": 2, "connectionTimeoutMs": 5000, "unknownOption": true },
                          "configVersion": 17 }
                        """;
                case "/api/v1/internal/resolve/datasource/forbidden" -> {
                    status = 403;
                    resp = "{\"status\":403,\"error\":\"FORBIDDEN\",\"message\":\"no enabled grant\",\"path\":\"" + path + "\"}";
                }
                case "/api/v1/internal/resolve/datasource/missing" -> {
                    status = 404;
                    resp = "not json at all";
                }
                case "/api/v1/internal/credentials/cred-1/material" -> resp = "{\"username\":\"SALES_APP\",\"secret\":\"s3cr3t\",\"version\":3}";
                case "/api/v1/internal/proxy/config" -> resp = """
                        { "configVersion": 17,
                          "listeners": [
                            { "name": "oracle-main", "engine": "ORACLE", "port": 1521,
                              "routes": [ { "match": "sales", "datasourceId": "ds-1", "databaseId": "db-1", "host": "oracle", "port": 1521, "serviceName": "FREEPDB1", "rewriteServiceName": true } ],
                              "defaultRoute": { "host": "oracle", "port": 1521, "serviceName": null, "rewriteServiceName": false } },
                            { "name": "postgres-main", "engine": "POSTGRES", "port": 5432, "routes": [], "defaultRoute": { "host": "pg", "port": 5432 } }
                          ],
                          "applications": [ { "id": "app-1", "name": "orders-service", "teamId": "team-1",
                                              "identityRules": { "programs": ["JDBC Thin Client"], "cidrs": ["10.20.0.0/16"] } } ],
                          "quotas": [ { "applicationId": "app-1", "datasourceId": "ds-1", "maxProxyConnections": 20 } ],
                          "datasourceQuotas": [ { "datasourceId": "ds-1", "maxProxyConnections": 200 } ] }
                        """;
                case "/api/v1/internal/slow" -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    resp = "{}";
                }
                default -> {
                    status = 500;
                    resp = "{\"status\":500,\"error\":\"INTERNAL\",\"message\":\"boom\"}";
                }
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        client = new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "svc-token",
                Duration.ofMillis(800));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void heartbeatPostsRecordAndReturnsConfigVersion() {
        Heartbeat hb = new Heartbeat(ComponentType.GATEWAY, "gw-1", "0.1.0", "gw-1.internal",
                Instant.parse("2026-10-05T10:00:00Z"), 16L, Heartbeat.Stats.gateway(10, 3, 0));
        Optional<Long> version = client.heartbeat(hb);
        assertThat(version).contains(17L);
        Call call = calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/v1/internal/heartbeat");
        assertThat(call.token()).isEqualTo("svc-token");
        assertThat(call.body().get("componentType").asText()).isEqualTo("GATEWAY");
        assertThat(call.body().get("stats").get("logicalSessions").asInt()).isEqualTo(10);
        assertThat(client.configVersion()).isEqualTo(18L);
        assertThat(calls.get(1).method()).isEqualTo("GET");
    }

    @Test
    void authenticateApplication() {
        ApplicationIdentity id = client.authenticateApplication("dbp_good");
        assertThat(id.applicationId()).isEqualTo("app-1");
        assertThat(id.name()).isEqualTo("orders-service");
        assertThat(id.teamName()).isEqualTo("sales-platform");
        assertThat(id.tags()).containsExactly("tier1");
        assertThat(calls.get(0).body().get("apiKey").asText()).isEqualTo("dbp_good");

        assertThatThrownBy(() -> client.authenticateApplication("dbp_bad"))
                .isInstanceOf(ControlPlaneException.class)
                .satisfies(e -> {
                    ControlPlaneException cpe = (ControlPlaneException) e;
                    assertThat(cpe.status()).isEqualTo(401);
                    assertThat(cpe.isUnauthorized()).isTrue();
                    assertThat(cpe.errorCode()).isEqualTo("UNAUTHORIZED");
                    assertThat(cpe.getMessage()).contains("unknown api key").contains("401");
                    assertThat(cpe.isRetryable()).isFalse();
                });
    }

    @Test
    void resolveDatasourceMapsNestedRecords() {
        DatasourceResolution r = client.resolveDatasource("sales", "app-1");
        assertThat(calls.get(0).path()).isEqualTo("/api/v1/internal/resolve/datasource/sales");
        assertThat(calls.get(0).query()).isEqualTo("applicationId=app-1");
        assertThat(r.datasource()).isEqualTo(new DatasourceResolution.DatasourceInfo("ds-1", "sales", "ACTIVE"));
        assertThat(r.grant().maxLogicalConnections()).isEqualTo(50);
        assertThat(r.grant().poolMode()).isEqualTo("TRANSACTION");
        assertThat(r.database().engine()).isEqualTo(Engine.ORACLE);
        assertThat(r.database().jdbcUrl()).isEqualTo("jdbc:oracle:thin:@//oracle:1521/FREEPDB1");
        assertThat(r.database().jdbcProperties()).containsEntry("oracle.jdbc.timezoneAsRegion", "false");
        assertThat(r.credential()).isEqualTo(new DatasourceResolution.CredentialRef("cred-1", "SALES_APP", 3));
        assertThat(r.poolPolicy().maxSize()).isEqualTo(20);
        assertThat(r.poolPolicy().idleTimeoutMsOr(1234)).isEqualTo(1234);
        assertThat(r.configVersion()).isEqualTo(17);

        assertThatThrownBy(() -> client.resolveDatasource("forbidden", "app-1"))
                .isInstanceOfSatisfying(ControlPlaneException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.isForbidden()).isTrue();
                });
        assertThatThrownBy(() -> client.resolveDatasource("missing", null))
                .isInstanceOfSatisfying(ControlPlaneException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.isNotFound()).isTrue();
                    assertThat(e.errorCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void credentialMaterialAndProxyConfig() {
        CredentialMaterial m = client.credentialMaterial("cred-1");
        assertThat(m.secret()).isEqualTo("s3cr3t");
        assertThat(m.toString()).doesNotContain("s3cr3t");

        ProxyConfig cfg = client.proxyConfig("proxy-1");
        assertThat(calls.get(1).query()).isEqualTo("proxyId=proxy-1");
        assertThat(cfg.configVersion()).isEqualTo(17);
        assertThat(cfg.listeners()).hasSize(2);
        ProxyConfig.Listener oracle = cfg.listeners().get(0);
        assertThat(oracle.engine()).isEqualTo(Engine.ORACLE);
        assertThat(oracle.port()).isEqualTo(1521);
        assertThat(oracle.routes().get(0).match()).isEqualTo("sales");
        assertThat(oracle.routes().get(0).rewriteServiceName()).isTrue();
        assertThat(oracle.defaultRoute().serviceName()).isNull();
        assertThat(cfg.listeners().get(1).routes()).isEmpty();
        assertThat(cfg.applications().get(0).identityRules().programs()).containsExactly("JDBC Thin Client");
        assertThat(cfg.applications().get(0).identityRules().serviceAliases()).isEmpty();
        assertThat(cfg.quotas().get(0).maxProxyConnections()).isEqualTo(20);
        assertThat(cfg.datasourceQuotas().get(0).datasourceId()).isEqualTo("ds-1");

        String json = TelemetryJson.toJson(cfg);
        assertThat(TelemetryJson.fromJson(json, ProxyConfig.class)).isEqualTo(cfg);
    }

    @Test
    void transportErrorsTimeoutsAndServerErrorsBecomeControlPlaneExceptions() throws IOException {
        assertThatThrownBy(() -> client.get("/api/v1/internal/slow", Map.class))
                .isInstanceOfSatisfying(ControlPlaneException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.isTransport()).isTrue();
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("timed out");
                });
        assertThatThrownBy(() -> client.get("/api/v1/internal/nope", Map.class))
                .isInstanceOfSatisfying(ControlPlaneException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.errorCode()).isEqualTo("INTERNAL");
                    assertThat(e.isRetryable()).isTrue();
                });
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        ControlPlaneClient dead = new ControlPlaneClient("http://127.0.0.1:" + deadPort, "t", Duration.ofMillis(500));
        assertThatThrownBy(dead::configVersion)
                .isInstanceOfSatisfying(ControlPlaneException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).contains("unreachable");
                    assertThat(e.path()).isEqualTo("/api/v1/internal/config-version");
                });
    }
}
