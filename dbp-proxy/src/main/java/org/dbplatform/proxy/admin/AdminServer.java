package org.dbplatform.proxy.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.metrics.ProxyMetrics;
import org.dbplatform.proxy.net.ListenerRuntime;
import org.dbplatform.proxy.net.ProxyServer;
import org.dbplatform.proxy.registry.ConnectionRegistry;
import org.dbplatform.proxy.registry.LiveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Admin endpoints on {@code DBP_PROXY_ADMIN_ADDRESS:DBP_PROXY_ADMIN_PORT} (0.0.0.0:7431): {@code GET /health},
 * {@code GET /metrics} (Prometheus), {@code GET /connections} (live connections) and {@code GET /config}
 * (effective configuration, no secrets). {@code /health} and {@code /metrics} are always open (health checks,
 * Prometheus scrape); {@code /connections} and {@code /config} require {@code X-DBP-Service-Token} when
 * {@code DBP_SERVICE_TOKEN} is set, because they expose client addresses, users, programs and the route table.
 */
public final class AdminServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AdminServer.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    public static final String TOKEN_HEADER = "X-DBP-Service-Token";

    private final HttpServer server;
    private final ProxyServer proxy;
    private final ConnectionRegistry registry;
    private final ProxyMetrics metrics;
    private final ProxySettings settings;
    private final Supplier<Map<String, Object>> status;

    public AdminServer(ProxySettings settings, ProxyServer proxy, ConnectionRegistry registry, ProxyMetrics metrics,
                       Supplier<Map<String, Object>> status) throws IOException {
        this.settings = settings;
        this.proxy = proxy;
        this.registry = registry;
        this.metrics = metrics;
        this.status = status == null ? Map::of : status;
        this.server = HttpServer.create(new InetSocketAddress(settings.adminAddress(), settings.adminPort()), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", ex -> json(ex, health()));
        server.createContext("/metrics", ex -> text(ex, metrics.scrape(), "text/plain; version=0.0.4; charset=utf-8"));
        server.createContext("/connections", ex -> {
            if (authorized(ex)) {
                json(ex, connections());
            }
        });
        server.createContext("/config", ex -> {
            if (authorized(ex)) {
                json(ex, config());
            }
        });
        server.createContext("/", ex -> {
            if ("/".equals(ex.getRequestURI().getPath())) {
                json(ex, Map.of("endpoints", List.of("/health", "/metrics", "/connections", "/config")));
            } else {
                text(ex, "not found", "text/plain", 404);
            }
        });
    }

    public void start() {
        server.start();
        LOG.info("admin API listening on {} ({} and {} {})", server.getAddress(), "/connections", "/config",
                settings.adminTokenRequired() ? "require " + TOKEN_HEADER : "are open: set DBP_SERVICE_TOKEN to protect them");
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Constant-time token check for the sensitive endpoints; answers 401 and returns false when it fails. */
    private boolean authorized(HttpExchange ex) throws IOException {
        String required = settings.adminToken();
        if (required == null) {
            return true;
        }
        String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), required.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        text(ex, "{\"status\":401,\"error\":\"UNAUTHORIZED\",\"message\":\"" + TOKEN_HEADER + " required\"}",
                "application/json; charset=utf-8", 401);
        return false;
    }

    Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, String> errors = proxy.listenerErrors();
        boolean allRunning = true;
        List<Map<String, Object>> ls = new ArrayList<>();
        for (ListenerRuntime l : proxy.listeners().values()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("name", l.config().name());
            lm.put("engine", l.config().engine().name());
            lm.put("port", l.boundPort());
            lm.put("running", l.isRunning());
            lm.put("inFlight", l.inFlight());
            lm.put("live", registry.countForListener(l.config().name()));
            ls.add(lm);
            allRunning &= l.isRunning();
        }
        m.put("status", errors.isEmpty() && allRunning ? "UP" : "DEGRADED");
        m.put("proxyId", settings.proxyId());
        m.put("mode", settings.controlPlaneMode() ? "control-plane" : "static");
        m.put("configVersion", proxy.current().configVersion());
        m.put("liveConnections", registry.size());
        m.put("listeners", ls);
        if (!errors.isEmpty()) {
            m.put("listenerErrors", errors);
        }
        m.putAll(status.get());
        return m;
    }

    List<Map<String, Object>> connections() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LiveConnection c : registry.snapshot(Integer.MAX_VALUE)) {
            out.add(c.toMap());
        }
        return out;
    }

    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("proxyId", settings.proxyId());
        s.put("listenAddress", settings.listenAddress());
        s.put("adminAddress", settings.adminAddress());
        s.put("adminPort", settings.adminPort());
        s.put("adminTokenRequired", settings.adminTokenRequired());
        s.put("idleTimeoutSeconds", settings.idleTimeoutSeconds());
        s.put("connectTimeoutMs", settings.connectTimeoutMs());
        s.put("handshakeTimeoutMs", settings.handshakeTimeoutMs());
        s.put("bufferBytes", settings.bufferBytes());
        s.put("controlPlaneUrl", settings.controlPlaneUrl());
        s.put("configPollSeconds", settings.configPollSeconds());
        s.put("heartbeatSeconds", settings.heartbeatSeconds());
        s.put("staticConfigPath", settings.staticConfigPath());
        s.put("strictAliases", settings.strictAliases());
        s.put("unknownAppMaxConnections", settings.unknownAppMaxConnections());
        m.put("settings", s);
        ProxyConfigDocument cfg = proxy.current();
        m.put("configVersion", cfg.configVersion());
        m.put("listeners", cfg.listeners());
        m.put("applications", cfg.applications());
        m.put("quotas", cfg.quotas());
        m.put("datasourceQuotas", cfg.datasourceQuotas());
        return m;
    }

    private static void json(HttpExchange ex, Object body) throws IOException {
        text(ex, JSON.writeValueAsString(body), "application/json; charset=utf-8");
    }

    private static void text(HttpExchange ex, String body, String contentType) throws IOException {
        text(ex, body, contentType, 200);
    }

    private static void text(HttpExchange ex, String body, String contentType, int status) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
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
