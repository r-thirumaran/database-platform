package org.dbplatform.controlplane.service.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory view of live connections: the last proxy heartbeat snapshot per proxy, the OPEN/CLOSE
 * stream of proxy connection events (used for PORT correlation by the Oracle/PostgreSQL runtime samplers)
 * and the latest collector session snapshot per database.
 */
@Component
public class LiveConnectionRegistry {

    /** A proxied backend connection: key is backendHost:backendPort/proxyLocalPort. */
    public record ProxyConnection(String connectionId, String proxyId, String backendHost, int backendPort, int proxyLocalPort,
                                  String applicationId, String application, String datasourceId, String datasource,
                                  String clientAddr, String program, String clientHost, String osUser, String dbUser,
                                  Instant openedAt, String engine, Instant lastSeen) {
        public String key() { return key(backendHost, backendPort, proxyLocalPort); }
        public static String key(String host, int port, int localPort) { return (host == null ? "" : host.toLowerCase()) + ":" + port + "/" + localPort; }
    }

    private final Map<String, ProxyConnection> proxyConnections = new ConcurrentHashMap<>();
    private final Map<String, List<LiveConnection>> proxySnapshots = new ConcurrentHashMap<>();
    private final Map<String, List<LiveConnection>> collectorSnapshots = new ConcurrentHashMap<>();

    public void opened(ProxyConnection c) { proxyConnections.put(c.key(), c); }

    public void closed(String backendHost, int backendPort, int proxyLocalPort) {
        proxyConnections.remove(ProxyConnection.key(backendHost, backendPort, proxyLocalPort));
    }

    /** Replaces the OPEN set for one proxy with the heartbeat snapshot (authoritative). */
    public void replaceProxyConnections(String proxyId, List<ProxyConnection> live) {
        proxyConnections.entrySet().removeIf(e -> proxyId.equals(e.getValue().proxyId()));
        for (ProxyConnection c : live) proxyConnections.put(c.key(), c);
    }

    public void forgetProxy(String proxyId) {
        proxyConnections.entrySet().removeIf(e -> proxyId.equals(e.getValue().proxyId()));
        proxySnapshots.remove(proxyId);
    }

    /** Correlates a backend session (its client port as seen by the database) with a proxied connection. */
    public Optional<ProxyConnection> correlate(String backendHost, int backendPort, int port) {
        ProxyConnection c = proxyConnections.get(ProxyConnection.key(backendHost, backendPort, port));
        if (c != null) return Optional.of(c);
        // host may be reported differently (ip vs name): fall back to port-only match when unique
        List<ProxyConnection> byPort = proxyConnections.values().stream().filter(x -> x.proxyLocalPort() == port && x.backendPort() == backendPort).toList();
        return byPort.size() == 1 ? Optional.of(byPort.get(0)) : Optional.empty();
    }

    public List<ProxyConnection> proxyConnections() { return new ArrayList<>(proxyConnections.values()); }

    public void setProxySnapshot(String proxyId, List<LiveConnection> rows) { proxySnapshots.put(proxyId, rows); }
    public void setCollectorSnapshot(String databaseId, List<LiveConnection> rows) { collectorSnapshots.put(databaseId, rows); }
    public void clearCollectorSnapshot(String databaseId) { collectorSnapshots.remove(databaseId); }

    public List<LiveConnection> proxyRows() {
        List<LiveConnection> out = new ArrayList<>();
        proxySnapshots.values().forEach(out::addAll);
        return out;
    }

    public List<LiveConnection> collectorRows() {
        List<LiveConnection> out = new ArrayList<>();
        collectorSnapshots.values().forEach(out::addAll);
        return out;
    }

    public List<LiveConnection> all() {
        List<LiveConnection> out = proxyRows();
        out.addAll(collectorRows());
        return out;
    }
}
