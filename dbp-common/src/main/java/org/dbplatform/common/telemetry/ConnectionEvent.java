package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Lifecycle event of one proxied connection, reported by the proxy. Field names follow
 * {@code docs/telemetry-events.md} exactly. {@code connectionId} is stable across the OPEN/CLOSE pair.
 */
public record ConnectionEvent(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("proxyId") String proxyId,
        @JsonProperty("eventType") ConnectionEventType eventType,
        @JsonProperty("listener") String listener,
        @JsonProperty("engine") Engine engine,
        @JsonProperty("connectionId") String connectionId,
        @JsonProperty("clientAddr") String clientAddr,
        @JsonProperty("clientPort") int clientPort,
        @JsonProperty("proxyLocalAddr") String proxyLocalAddr,
        @JsonProperty("proxyLocalPort") int proxyLocalPort,
        @JsonProperty("backendHost") String backendHost,
        @JsonProperty("backendPort") int backendPort,
        @JsonProperty("requestedService") String requestedService,
        @JsonProperty("resolvedService") String resolvedService,
        @JsonProperty("applicationId") String applicationId,
        @JsonProperty("application") String application,
        @JsonProperty("identitySource") IdentitySource identitySource,
        @JsonProperty("datasourceId") String datasourceId,
        @JsonProperty("datasource") String datasource,
        @JsonProperty("program") String program,
        @JsonProperty("clientHost") String clientHost,
        @JsonProperty("osUser") String osUser,
        @JsonProperty("dbUser") String dbUser,
        @JsonProperty("openedAt") Instant openedAt,
        @JsonProperty("closedAt") Instant closedAt,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("bytesIn") long bytesIn,
        @JsonProperty("bytesOut") long bytesOut,
        @JsonProperty("reason") String reason) {

    public static final int MAX_REASON = 500;

    public ConnectionEvent {
        reason = QueryEvent.truncate(reason, MAX_REASON);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.eventId = eventId; b.timestamp = timestamp; b.proxyId = proxyId; b.eventType = eventType;
        b.listener = listener; b.engine = engine; b.connectionId = connectionId; b.clientAddr = clientAddr;
        b.clientPort = clientPort; b.proxyLocalAddr = proxyLocalAddr; b.proxyLocalPort = proxyLocalPort;
        b.backendHost = backendHost; b.backendPort = backendPort; b.requestedService = requestedService;
        b.resolvedService = resolvedService; b.applicationId = applicationId; b.application = application;
        b.identitySource = identitySource; b.datasourceId = datasourceId; b.datasource = datasource;
        b.program = program; b.clientHost = clientHost; b.osUser = osUser; b.dbUser = dbUser;
        b.openedAt = openedAt; b.closedAt = closedAt; b.durationMs = durationMs; b.bytesIn = bytesIn;
        b.bytesOut = bytesOut; b.reason = reason;
        return b;
    }

    /** Mutable builder; defaults: {@code timestamp=now}, {@code identitySource=NONE}. */
    public static final class Builder {
        private String eventId;
        private Instant timestamp = Instant.now();
        private String proxyId;
        private ConnectionEventType eventType;
        private String listener;
        private Engine engine;
        private String connectionId;
        private String clientAddr;
        private int clientPort;
        private String proxyLocalAddr;
        private int proxyLocalPort;
        private String backendHost;
        private int backendPort;
        private String requestedService;
        private String resolvedService;
        private String applicationId;
        private String application;
        private IdentitySource identitySource = IdentitySource.NONE;
        private String datasourceId;
        private String datasource;
        private String program;
        private String clientHost;
        private String osUser;
        private String dbUser;
        private Instant openedAt;
        private Instant closedAt;
        private long durationMs;
        private long bytesIn;
        private long bytesOut;
        private String reason;

        private Builder() {}

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder proxyId(String v) { this.proxyId = v; return this; }
        public Builder eventType(ConnectionEventType v) { this.eventType = v; return this; }
        public Builder listener(String v) { this.listener = v; return this; }
        public Builder engine(Engine v) { this.engine = v; return this; }
        public Builder connectionId(String v) { this.connectionId = v; return this; }
        public Builder clientAddr(String v) { this.clientAddr = v; return this; }
        public Builder clientPort(int v) { this.clientPort = v; return this; }
        public Builder proxyLocalAddr(String v) { this.proxyLocalAddr = v; return this; }
        public Builder proxyLocalPort(int v) { this.proxyLocalPort = v; return this; }
        public Builder backendHost(String v) { this.backendHost = v; return this; }
        public Builder backendPort(int v) { this.backendPort = v; return this; }
        public Builder requestedService(String v) { this.requestedService = v; return this; }
        public Builder resolvedService(String v) { this.resolvedService = v; return this; }
        public Builder applicationId(String v) { this.applicationId = v; return this; }
        public Builder application(String v) { this.application = v; return this; }
        public Builder identitySource(IdentitySource v) { this.identitySource = v; return this; }
        public Builder datasourceId(String v) { this.datasourceId = v; return this; }
        public Builder datasource(String v) { this.datasource = v; return this; }
        public Builder program(String v) { this.program = v; return this; }
        public Builder clientHost(String v) { this.clientHost = v; return this; }
        public Builder osUser(String v) { this.osUser = v; return this; }
        public Builder dbUser(String v) { this.dbUser = v; return this; }
        public Builder openedAt(Instant v) { this.openedAt = v; return this; }
        public Builder closedAt(Instant v) { this.closedAt = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder bytesIn(long v) { this.bytesIn = v; return this; }
        public Builder bytesOut(long v) { this.bytesOut = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }

        public ConnectionEvent build() {
            return new ConnectionEvent(eventId, timestamp, proxyId, eventType, listener, engine, connectionId,
                    clientAddr, clientPort, proxyLocalAddr, proxyLocalPort, backendHost, backendPort,
                    requestedService, resolvedService, applicationId, application, identitySource, datasourceId,
                    datasource, program, clientHost, osUser, dbUser, openedAt, closedAt, durationMs, bytesIn,
                    bytesOut, reason);
        }
    }
}
