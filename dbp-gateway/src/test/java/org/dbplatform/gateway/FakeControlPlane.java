package org.dbplatform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process stand-in for the control plane's internal endpoints. Records every request for assertions.
 */
public final class FakeControlPlane implements AutoCloseable {

    public static final String TOKEN = "test-token";
    public static final String API_KEY = "dbp_test_secret";

    public record Request(String method, String path, String token, String body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public final List<JsonNode> queryEvents = new CopyOnWriteArrayList<>();
    public final List<JsonNode> poolStats = new CopyOnWriteArrayList<>();
    public final List<JsonNode> heartbeats = new CopyOnWriteArrayList<>();
    public final AtomicLong configVersion = new AtomicLong(1);
    public volatile String jdbcUrl;
    public volatile String credUser = "app1";
    public volatile String credSecret = "pw1";
    public volatile int credVersion = 1;
    public volatile int maxLogicalConnections = 2;
    public volatile boolean readOnly;

    private final HttpServer server;

    public FakeControlPlane(String jdbcUrl) throws IOException {
        this.jdbcUrl = jdbcUrl;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/api/v1/internal/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void rotateCredential(String user, String secret, int version) {
        credUser = user;
        credSecret = secret;
        credVersion = version;
        configVersion.incrementAndGet();
    }

    public List<Request> requests(String method, String pathPrefix) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath() + (ex.getRequestURI().getQuery() != null ? "?" + ex.getRequestURI().getQuery() : "");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String token = ex.getRequestHeaders().getFirst("X-DBP-Service-Token");
        requests.add(new Request(ex.getRequestMethod(), path, token, body));
        if (!TOKEN.equals(token)) {
            reply(ex, 401, "{\"status\":401,\"error\":\"UNAUTHORIZED\",\"message\":\"bad service token\"}");
            return;
        }
        String p = ex.getRequestURI().getPath();
        try {
            if (p.equals("/api/v1/internal/auth/application")) {
                JsonNode req = JSON.readTree(body);
                if (API_KEY.equals(req.path("apiKey").asText())) {
                    reply(ex, 200, "{\"applicationId\":\"app-1\",\"name\":\"orders-service\",\"teamId\":\"team-1\",\"teamName\":\"sales\",\"tags\":[]}");
                } else {
                    reply(ex, 401, "{\"status\":401,\"error\":\"UNAUTHORIZED\",\"message\":\"invalid api key\"}");
                }
            } else if (p.startsWith("/api/v1/internal/resolve/datasource/")) {
                String name = p.substring("/api/v1/internal/resolve/datasource/".length());
                if (name.equals("forbidden")) {
                    reply(ex, 403, "{\"status\":403,\"error\":\"FORBIDDEN\",\"message\":\"no grant\"}");
                } else if (!name.equals("sales")) {
                    reply(ex, 404, "{\"status\":404,\"error\":\"NOT_FOUND\",\"message\":\"unknown datasource\"}");
                } else {
                    reply(ex, 200, resolution());
                }
            } else if (p.startsWith("/api/v1/internal/credentials/") && p.endsWith("/material")) {
                ObjectNode n = JSON.createObjectNode();
                n.put("username", credUser).put("secret", credSecret).put("version", credVersion);
                reply(ex, 200, n.toString());
            } else if (p.equals("/api/v1/internal/config-version")) {
                reply(ex, 200, "{\"configVersion\":" + configVersion.get() + "}");
            } else if (p.equals("/api/v1/internal/heartbeat")) {
                heartbeats.add(JSON.readTree(body));
                reply(ex, 200, "{\"configVersion\":" + configVersion.get() + "}");
            } else if (p.equals("/api/v1/internal/telemetry/queries")) {
                ArrayNode arr = (ArrayNode) JSON.readTree(body);
                arr.forEach(queryEvents::add);
                reply(ex, 202, "{\"accepted\":" + arr.size() + "}");
            } else if (p.equals("/api/v1/internal/telemetry/pools")) {
                ArrayNode arr = (ArrayNode) JSON.readTree(body);
                arr.forEach(poolStats::add);
                reply(ex, 202, "{\"accepted\":" + arr.size() + "}");
            } else {
                reply(ex, 404, "{\"status\":404,\"error\":\"NOT_FOUND\",\"message\":\"" + p + "\"}");
            }
        } catch (RuntimeException e) {
            reply(ex, 500, "{\"status\":500,\"error\":\"SERVER_ERROR\",\"message\":\"" + e + "\"}");
        }
    }

    private String resolution() {
        ObjectNode r = JSON.createObjectNode();
        r.putObject("datasource").put("id", "ds-1").put("name", "sales").put("state", "ACTIVE");
        r.putObject("grant").put("maxLogicalConnections", maxLogicalConnections).put("readOnly", readOnly).put("poolMode", "TRANSACTION");
        ObjectNode db = r.putObject("database");
        db.put("id", "db-1").put("name", "sales-h2").put("engine", "H2").put("host", "localhost").put("port", 0)
                .put("serviceName", "cpdb").put("jdbcUrl", jdbcUrl);
        db.putObject("jdbcProperties");
        r.putObject("credential").put("id", "cred-1").put("username", credUser).put("version", credVersion);
        r.putObject("poolPolicy").put("maxSize", 3).put("minIdle", 0).put("connectionTimeoutMs", 1000)
                .put("idleTimeoutMs", 600000).put("maxLifetimeMs", 1800000).put("statementTimeoutMs", 0);
        r.put("configVersion", configVersion.get());
        return r.toString();
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
