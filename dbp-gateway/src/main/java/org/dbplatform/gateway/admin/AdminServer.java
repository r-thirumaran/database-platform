package org.dbplatform.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.gateway.GatewayConfig;
import org.dbplatform.gateway.Version;
import org.dbplatform.gateway.control.Resolver;
import org.dbplatform.gateway.pool.PhysicalPool;
import org.dbplatform.gateway.pool.PoolManager;
import org.dbplatform.gateway.session.LogicalSession;
import org.dbplatform.gateway.session.SessionRegistry;
import org.dbplatform.gateway.telemetry.GatewayMetrics;
import org.dbplatform.gateway.telemetry.GatewayTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Admin HTTP endpoints on {@code DBP_GATEWAY_ADMIN_PORT}: {@code /health}, {@code /metrics} (Prometheus),
 * {@code /sessions}, {@code /pools}.
 */
public final class AdminServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdminServer.class);
    private static final ObjectMapper JSON = TelemetryJson.mapper();

    private final GatewayConfig config;
    private final SessionRegistry sessions;
    private final PoolManager pools;
    private final GatewayTelemetry telemetry;
    private final GatewayMetrics metrics;
    private final Resolver resolver;
    private final Instant startedAt = Instant.now();
    private HttpServer server;

    public AdminServer(GatewayConfig config, SessionRegistry sessions, PoolManager pools, GatewayTelemetry telemetry,
                       GatewayMetrics metrics, Resolver resolver) {
        this.config = config;
        this.sessions = sessions;
        this.pools = pools;
        this.telemetry = telemetry;
        this.metrics = metrics;
        this.resolver = resolver;
    }

    public synchronized void start() throws IOException {
        if (server != null || config.adminPort() < 0) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(config.bindAddress()), config.adminPort()), 16);
        server.createContext("/health", ex -> json(ex, health()));
        server.createContext("/metrics", ex -> text(ex, metrics.scrape(), "text/plain; version=0.0.4; charset=utf-8"));
        server.createContext("/sessions", ex -> json(ex, sessionList()));
        server.createContext("/pools", ex -> json(ex, poolList()));
        server.createContext("/", ex -> text(ex, "dbp-gateway " + Version.CURRENT + "\n/health /metrics /sessions /pools\n",
                "text/plain; charset=utf-8"));
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dbp-admin-", 0).factory()));
        server.start();
        LOG.info("admin endpoints on {}:{}", config.bindAddress(), port());
    }

    public int port() {
        return server == null ? config.adminPort() : server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ payloads

    Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean cpOk = resolver.reachable();
        m.put("status", "UP");
        m.put("gatewayId", config.gatewayId());
        m.put("version", Version.CURRENT);
        m.put("startedAt", startedAt.toString());
        m.put("uptimeSeconds", (System.currentTimeMillis() - startedAt.toEpochMilli()) / 1000);
        m.put("logicalSessions", sessions.size());
        m.put("pinnedSessions", sessions.pinned());
        m.put("physicalConnections", pools.physicalConnections());
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("mode", resolver.mode());
        cp.put("reachable", cpOk);
        cp.put("configVersion", resolver.configVersion().orElse(null));
        cp.put("telemetryEnabled", telemetry.enabled());
        cp.put("eventsDropped", telemetry.eventsDropped());
        cp.put("lastHeartbeatOk", telemetry.lastHeartbeatOk().map(Instant::toString).orElse(null));
        m.put("controlPlane", cp);
        m.put("pools", poolList());
        return m;
    }

    List<Map<String, Object>> poolList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PhysicalPool p : pools.pools()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("datasources", p.datasourceNames());
            m.put("engine", p.engine());
            m.put("jdbcUrl", maskSecrets(p.settings().jdbcUrl()));
            m.put("username", p.settings().username());
            m.put("credentialVersion", p.settings().credentialVersion());
            m.put("poolMode", p.settings().poolMode());
            m.put("active", p.activeConnections());
            m.put("idle", p.idleConnections());
            m.put("waiting", p.waitingThreads());
            m.put("total", p.totalConnections());
            m.put("max", p.maxConnections());
            m.put("pinnedSessions", p.pinnedSessions());
            m.put("draining", p.isDraining());
            m.put("createdAt", Instant.ofEpochMilli(p.createdAt()).toString());
            out.add(m);
        }
        return out;
    }

    List<Map<String, Object>> sessionList() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LogicalSession s : sessions.sessions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.id());
            m.put("application", s.identity().application());
            m.put("applicationId", s.identity().applicationId());
            m.put("team", s.identity().team());
            m.put("datasource", s.datasource());
            m.put("poolMode", s.poolMode());
            m.put("pinned", s.isPinned());
            m.put("inTransaction", s.inTransaction());
            m.put("autoCommit", s.settings().autoCommit());
            m.put("openCursors", s.openCursors());
            m.put("statements", s.statements());
            m.put("idleSeconds", (now - s.lastActivityMillis()) / 1000);
            m.put("ageSeconds", (now - s.createdAtMillis()) / 1000);
            m.put("pinnedSeconds", s.isPinned() ? (now - s.pinnedSinceMillis()) / 1000 : null);
            m.put("clientAddress", s.clientAddress());
            m.put("clientInfo", s.settings().clientInfo());
            m.put("schema", s.settings().schema());
            out.add(m);
        }
        return out;
    }

    /** Masks {@code password=...}-style values a DBA may have embedded in a physical JDBC URL. */
    static String maskSecrets(String url) {
        return url == null ? null : url.replaceAll("(?i)(password|pwd|secret)(\\s*=\\s*)[^;&)]*", "$1$2***");
    }

    // ------------------------------------------------------------------ http helpers

    private static void json(HttpExchange ex, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void text(HttpExchange ex, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
