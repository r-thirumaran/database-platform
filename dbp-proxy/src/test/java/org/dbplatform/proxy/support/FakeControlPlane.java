package org.dbplatform.proxy.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.proxy.config.ProxyConfigDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Just enough of the control plane's internal API for proxy tests. Records everything it receives. */
public final class FakeControlPlane implements AutoCloseable {
    private static final ObjectMapper JSON = TelemetryJson.mapper();

    private final HttpServer server;
    private final String token;
    private volatile ProxyConfigDocument config;
    public final List<Map<String, Object>> connectionEvents = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> heartbeats = new CopyOnWriteArrayList<>();
    public final List<String> unauthorized = new CopyOnWriteArrayList<>();
    public final List<String> configRequests = new CopyOnWriteArrayList<>();

    public FakeControlPlane(String token, ProxyConfigDocument initial) throws IOException {
        this.token = token;
        this.config = initial;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.createContext("/api/v1/internal/proxy/config", ex -> {
            if (!auth(ex)) {
                return;
            }
            configRequests.add(ex.getRequestURI().getQuery());
            reply(ex, 200, JSON.writeValueAsBytes(config));
        });
        server.createContext("/api/v1/internal/config-version", ex -> {
            if (!auth(ex)) {
                return;
            }
            reply(ex, 200, ("{\"configVersion\":" + config.configVersion() + "}").getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/v1/internal/telemetry/connections", ex -> {
            if (!auth(ex)) {
                return;
            }
            List<Map<String, Object>> events = JSON.readValue(ex.getRequestBody().readAllBytes(), new TypeReference<>() {
            });
            connectionEvents.addAll(events);
            reply(ex, 202, ("{\"accepted\":" + events.size() + "}").getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/v1/internal/heartbeat", ex -> {
            if (!auth(ex)) {
                return;
            }
            Map<String, Object> hb = JSON.readValue(ex.getRequestBody().readAllBytes(), new TypeReference<>() {
            });
            heartbeats.add(hb);
            reply(ex, 200, ("{\"configVersion\":" + config.configVersion() + "}").getBytes(StandardCharsets.UTF_8));
        });
        server.start();
    }

    private boolean auth(HttpExchange ex) throws IOException {
        String t = ex.getRequestHeaders().getFirst("X-DBP-Service-Token");
        if (!token.equals(t)) {
            unauthorized.add(ex.getRequestURI().getPath());
            reply(ex, 401, "{\"status\":401,\"error\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return true;
    }

    private static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setConfig(ProxyConfigDocument c) {
        this.config = c;
    }

    public List<Map<String, Object>> events(String type) {
        return connectionEvents.stream().filter(e -> type.equals(e.get("eventType"))).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
