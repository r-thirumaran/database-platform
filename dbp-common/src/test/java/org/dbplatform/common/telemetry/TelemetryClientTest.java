package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.common.testutil.Await.await;

class TelemetryClientTest {

    record Received(String path, String token, String contentType, JsonNode body) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusByPath = new ConcurrentHashMap<>();
    private volatile int responseStatus = 202;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode node = TelemetryJson.mapper().readTree(body);
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-DBP-Service-Token"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), node));
            statusByPath.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new AtomicInteger()).incrementAndGet();
            byte[] resp = "{\"accepted\":1}".getBytes();
            exchange.sendResponseHeaders(responseStatus, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void batchesEventsPerEndpointWithTokenAndSpecFieldNames() {
        try (TelemetryClient client = TelemetryClient.builder(baseUrl, "secret-token")
                .flushInterval(Duration.ofMillis(100)).build()) {
            client.record(QueryEvent.builder().eventId("q1").gatewayId("gw-1").engine(Engine.ORACLE)
                    .operation(SqlOperation.SELECT).sqlHash("h").sqlNormalized("SELECT ?")
                    .tables(List.of(TableAccess.read("SALES", "CUSTOMER"))).durationMs(3).rows(1).build());
            client.record(QueryEvent.builder().eventId("q2").gatewayId("gw-1").engine(Engine.ORACLE).build());
            client.record(ConnectionEvent.builder().eventId("c1").proxyId("p-1").eventType(ConnectionEventType.OPEN)
                    .connectionId("conn-1").engine(Engine.POSTGRES).proxyLocalPort(40321).build());
            client.record(new PoolStats(Instant.now(), "gw-1", "sales", "ds", "db", Engine.ORACLE,
                    1, 2, 0, 3, 10, 5, 1, 1));

            await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= 3);
        }
        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(r -> {
            assertThat(r.token()).isEqualTo("secret-token");
            assertThat(r.contentType()).startsWith("application/json");
            assertThat(r.body().isArray()).isTrue();
        });
        Received queries = byPath("/api/v1/internal/telemetry/queries");
        assertThat(queries.body()).hasSize(2);
        assertThat(queries.body().get(0).get("eventId").asText()).isEqualTo("q1");
        assertThat(queries.body().get(0).get("tables").get(0).get("schema").asText()).isEqualTo("SALES");
        assertThat(queries.body().get(0).get("tables").get(0).get("access").asText()).isEqualTo("READ");
        assertThat(queries.body().get(0).get("operation").asText()).isEqualTo("SELECT");
        assertThat(queries.body().get(0).get("durationMs").asLong()).isEqualTo(3);
        assertThat(queries.body().get(0).get("sqlNormalized").asText()).isEqualTo("SELECT ?");
        assertThat(queries.body().get(0).get("timestamp").asText()).endsWith("Z");

        Received connections = byPath("/api/v1/internal/telemetry/connections");
        assertThat(connections.body()).hasSize(1);
        assertThat(connections.body().get(0).get("eventType").asText()).isEqualTo("OPEN");
        assertThat(connections.body().get(0).get("proxyLocalPort").asInt()).isEqualTo(40321);
        assertThat(connections.body().get(0).get("connectionId").asText()).isEqualTo("conn-1");

        Received pools = byPath("/api/v1/internal/telemetry/pools");
        assertThat(pools.body()).hasSize(1);
        assertThat(pools.body().get(0).get("max").asInt()).isEqualTo(10);
        assertThat(pools.body().get(0).get("logicalSessions").asInt()).isEqualTo(5);
    }

    @Test
    void splitsIntoBatchesOfAtMost500AndFlushesEarlyWhenFull() {
        try (TelemetryClient client = TelemetryClient.builder(baseUrl, "t")
                .flushInterval(Duration.ofSeconds(30)).build()) {
            for (int i = 0; i < 1200; i++) {
                client.record(QueryEvent.builder().eventId("q" + i).build());
            }
            // 1000 events = two full batches go out immediately, without waiting for the 30 s interval
            await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= 2);
            assertThat(received.get(0).body()).hasSize(500);
            assertThat(received.get(1).body()).hasSize(500);
            client.flush();
            await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= 3);
            assertThat(received.get(2).body()).hasSize(200);
            await().atMost(Duration.ofSeconds(5)).until(() -> client.sentCount() == 1200); // counted after the response
            assertThat(client.droppedCount()).isZero();
        }
        // close() with nothing left does not post anything else
        assertThat(received).hasSize(3);
        assertThat(received.get(0).body().get(0).get("eventId").asText()).isEqualTo("q0");
        assertThat(received.get(2).body().get(199).get("eventId").asText()).isEqualTo("q1199");
    }

    @Test
    void closeFlushesRemainingEvents() {
        TelemetryClient client = TelemetryClient.builder(baseUrl, "t").flushInterval(Duration.ofSeconds(30)).build();
        client.record(QueryEvent.builder().eventId("last").build());
        client.close();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).body().get(0).get("eventId").asText()).isEqualTo("last");
        // after close, events are dropped and counted, never thrown
        client.record(QueryEvent.builder().eventId("late").build());
        assertThat(client.droppedCount()).isEqualTo(1);
        client.close(); // idempotent
    }

    @Test
    void dropsOldestWhenQueueIsFullWhileServerIsDown() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        TelemetryClient client = TelemetryClient.builder("http://127.0.0.1:" + deadPort, "t")
                .queueCapacity(100).flushInterval(Duration.ofMillis(50)).connectTimeout(Duration.ofMillis(500))
                .requestTimeout(Duration.ofMillis(500)).build();
        for (int i = 0; i < 250; i++) {
            client.record(QueryEvent.builder().eventId("q" + i).build()); // never throws
        }
        assertThat(client.queuedCount()).isLessThanOrEqualTo(100);
        assertThat(client.droppedCount()).isGreaterThanOrEqualTo(150);
        await().atMost(Duration.ofSeconds(5)).until(() -> client.failedBatchCount() >= 1);
        // the failed batch is kept (re-queued) for a retry while the queue is not full
        assertThat(client.queuedCount()).isLessThanOrEqualTo(100);
        long droppedBeforeClose = client.droppedCount();
        client.close(); // final delivery fails: remaining events are dropped and counted
        assertThat(client.droppedCount()).isEqualTo(droppedBeforeClose + 100);
        assertThat(client.sentCount()).isZero();
        assertThat(client.queuedCount()).isZero();
    }

    @Test
    void retriesAfterServerErrorAndDropsOnClientError() {
        responseStatus = 503;
        try (TelemetryClient client = TelemetryClient.builder(baseUrl, "t").flushInterval(Duration.ofMillis(50)).build()) {
            client.record(QueryEvent.builder().eventId("retry-me").build());
            await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= 2); // retried at least once
            assertThat(client.droppedCount()).isZero();
            responseStatus = 202;
            await().atMost(Duration.ofSeconds(5)).until(() -> client.sentCount() == 1);

            responseStatus = 400; // rejected payload: dropped, not retried
            client.record(QueryEvent.builder().eventId("bad").build());
            await().atMost(Duration.ofSeconds(5)).until(() -> client.droppedCount() == 1);
            int posts = received.size();
            client.flush();
            assertThat(received).hasSize(posts);
        }
    }

    @Test
    void fromEnvReadsConfiguration() {
        org.dbplatform.common.util.Env.setEnvReader(k -> switch (k) {
            case "DBP_CONTROL_PLANE_URL" -> baseUrl;
            case "DBP_SERVICE_TOKEN" -> "env-token";
            case "DBP_TELEMETRY_FLUSH_MS" -> "50";
            case "DBP_TELEMETRY_QUEUE_SIZE" -> "7";
            default -> null;
        });
        try (TelemetryClient client = TelemetryClient.fromEnv().build()) {
            assertThat(client.capacity()).isEqualTo(7);
            assertThat(client.baseUrl()).isEqualTo(baseUrl.substring(0, baseUrl.length() - 1));
            client.record(QueryEvent.builder().eventId("e").build());
            await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
            assertThat(received.get(0).token()).isEqualTo("env-token");
        } finally {
            org.dbplatform.common.util.Env.setEnvReader(null);
        }
    }

    private Received byPath(String path) {
        return received.stream().filter(r -> r.path().equals(path)).findFirst().orElseThrow();
    }
}
