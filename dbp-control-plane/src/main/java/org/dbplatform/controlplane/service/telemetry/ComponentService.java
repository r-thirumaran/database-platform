package org.dbplatform.controlplane.service.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dbplatform.common.telemetry.ConnectionEvent;
import org.dbplatform.common.telemetry.Heartbeat;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.Component;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.ComponentRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.dbplatform.controlplane.service.ConfigVersionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Heartbeats from gateways and proxies; keeps the proxy live-connection snapshot. */
@Service
@Transactional
public class ComponentService {
    private final ComponentRepository components;
    private final ApplicationRepository applications;
    private final TeamRepository teams;
    private final LiveConnectionRegistry live;
    private final ConfigVersionService configVersion;
    private final DbpProperties props;

    public ComponentService(ComponentRepository components, ApplicationRepository applications, TeamRepository teams,
                            LiveConnectionRegistry live, ConfigVersionService configVersion, DbpProperties props) {
        this.components = components; this.applications = applications; this.teams = teams;
        this.live = live; this.configVersion = configVersion; this.props = props;
    }

    /** Stores the heartbeat (raw stats kept as JSON, live connections turned into the proxy snapshot). */
    public long heartbeat(Map<String, Object> body) {
        Heartbeat hb = TelemetryJson.mapper().convertValue(body, Heartbeat.class);
        if (hb.componentType() == null || hb.componentId() == null || hb.componentId().isBlank()) {
            throw new org.dbplatform.controlplane.api.error.ApiException.BadRequest("componentType and componentId are required");
        }
        Enums.ComponentType type = Enums.ComponentType.valueOf(hb.componentType().name());
        Component c = components.findByComponentTypeAndComponentId(type, hb.componentId()).orElseGet(() -> {
            Component n = new Component();
            n.setId(Ids.newId());
            n.setComponentType(type);
            n.setComponentId(hb.componentId());
            return n;
        });
        c.setVersion(hb.version());
        c.setHost(hb.host());
        c.setStartedAt(hb.startedAt());
        c.setLastHeartbeat(Instant.now());
        c.setConfigVersion(hb.configVersion());
        Map<String, Object> stats = new LinkedHashMap<>();
        Object rawStats = body.get("stats");
        if (rawStats instanceof Map<?, ?> m) m.forEach((k, v) -> { if (!"liveConnections".equals(k)) stats.put(String.valueOf(k), v); });
        stats.put("liveConnections", hb.stats().liveConnections().size());
        c.setStatsJson(Json.write(stats));
        List<ConnectionEvent> liveConns = hb.stats().liveConnections();
        c.setLiveConnectionsJson(TelemetryJson.toJson(liveConns));
        components.save(c);

        if (type == Enums.ComponentType.PROXY) {
            List<LiveConnectionRegistry.ProxyConnection> pcs = new ArrayList<>();
            List<LiveConnection> rows = new ArrayList<>();
            Map<String, Application> appCache = new LinkedHashMap<>();
            for (ConnectionEvent e : liveConns) {
                pcs.add(toProxyConnection(hb.componentId(), e));
                rows.add(toLiveRow(e, appCache));
            }
            live.replaceProxyConnections(hb.componentId(), pcs);
            live.setProxySnapshot(hb.componentId(), rows);
        }
        return configVersion.current();
    }

    public static LiveConnectionRegistry.ProxyConnection toProxyConnection(String proxyId, ConnectionEvent e) {
        return new LiveConnectionRegistry.ProxyConnection(e.connectionId(), proxyId, e.backendHost(), e.backendPort(), e.proxyLocalPort(),
                e.applicationId(), e.application(), e.datasourceId(), e.datasource(), e.clientAddr(), e.program(), e.clientHost(),
                e.osUser(), e.dbUser(), e.openedAt() != null ? e.openedAt() : e.timestamp(), e.engine() == null ? null : e.engine().name(), Instant.now());
    }

    private LiveConnection toLiveRow(ConnectionEvent e, Map<String, Application> cache) {
        String appName = e.application();
        String teamName = null;
        if (e.applicationId() != null) {
            Application a = cache.computeIfAbsent(e.applicationId(), id -> applications.findById(id).orElse(null));
            if (a != null) {
                appName = a.getName();
                if (a.getTeamId() != null) teamName = teams.findById(a.getTeamId()).map(Team::getName).orElse(null);
            }
        }
        Instant opened = e.openedAt() != null ? e.openedAt() : e.timestamp();
        Long dur = opened == null ? null : Duration.between(opened, Instant.now()).toSeconds();
        return new LiveConnection("PROXY", appName, teamName, e.datasource(), e.backendHost() == null ? null : e.backendHost() + ":" + e.backendPort(),
                e.engine() == null ? null : e.engine().name(), e.clientAddr(), e.program(), e.clientHost(), e.osUser(), e.dbUser(),
                "OPEN", opened, dur, null, null, e.applicationId(), null, e.datasourceId(), e.proxyLocalPort());
    }

    public record ComponentView(Enums.ComponentType componentType, String componentId, String version, String host, Instant startedAt,
                                Instant lastHeartbeat, boolean healthy, Long configVersion, Map<String, Object> stats) {}

    @Transactional(readOnly = true)
    public List<ComponentView> list() {
        Instant threshold = Instant.now().minusSeconds(props.getComponentHealthySeconds());
        List<ComponentView> out = new ArrayList<>();
        for (Component c : components.findAllByOrderByComponentTypeAscComponentIdAsc()) {
            Map<String, Object> stats = Json.read(c.getStatsJson(), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            out.add(new ComponentView(c.getComponentType(), c.getComponentId(), c.getVersion(), c.getHost(), c.getStartedAt(),
                    c.getLastHeartbeat(), c.getLastHeartbeat().isAfter(threshold), c.getConfigVersion(), stats == null ? Map.of() : stats));
        }
        return out;
    }

    public boolean isHealthy(Component c) {
        return c.getLastHeartbeat() != null && c.getLastHeartbeat().isAfter(Instant.now().minusSeconds(props.getComponentHealthySeconds()));
    }
}
