package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Periodic liveness report of a gateway or proxy ({@code POST /api/v1/internal/heartbeat}). Field names
 * follow {@code docs/telemetry-events.md} exactly. {@code configVersion} is the configuration version the
 * component currently runs with ({@code null} when it has none yet).
 */
public record Heartbeat(
        @JsonProperty("componentType") ComponentType componentType,
        @JsonProperty("componentId") String componentId,
        @JsonProperty("version") String version,
        @JsonProperty("host") String host,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("configVersion") Long configVersion,
        @JsonProperty("stats") Stats stats) {

    /** Maximum number of live connections a proxy includes in a heartbeat. */
    public static final int MAX_LIVE_CONNECTIONS = 2000;

    public Heartbeat {
        if (stats == null) {
            stats = new Stats(0, 0, 0, List.of());
        }
    }

    /**
     * Component statistics. {@code liveConnections} is the proxy's snapshot of currently open
     * connections (OPEN events, max {@value #MAX_LIVE_CONNECTIONS}); gateways send an empty list.
     */
    public record Stats(
            @JsonProperty("logicalSessions") long logicalSessions,
            @JsonProperty("physicalConnections") long physicalConnections,
            @JsonProperty("eventsDropped") long eventsDropped,
            @JsonProperty("liveConnections") List<ConnectionEvent> liveConnections) {

        public Stats {
            if (liveConnections == null) {
                liveConnections = List.of();
            } else if (liveConnections.size() > MAX_LIVE_CONNECTIONS) {
                liveConnections = List.copyOf(liveConnections.subList(0, MAX_LIVE_CONNECTIONS));
            } else {
                liveConnections = List.copyOf(liveConnections);
            }
        }

        public static Stats gateway(long logicalSessions, long physicalConnections, long eventsDropped) {
            return new Stats(logicalSessions, physicalConnections, eventsDropped, List.of());
        }

        public static Stats proxy(long physicalConnections, long eventsDropped, List<ConnectionEvent> live) {
            return new Stats(0, physicalConnections, eventsDropped, live);
        }
    }
}
