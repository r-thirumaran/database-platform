package org.dbplatform.common.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One executed statement, reported by the gateway. Field names follow {@code docs/telemetry-events.md}
 * exactly. {@code rows} is {@code -1} when unknown; {@code errorMessage} is truncated to 500 chars.
 */
public record QueryEvent(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("gatewayId") String gatewayId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("applicationId") String applicationId,
        @JsonProperty("application") String application,
        @JsonProperty("team") String team,
        @JsonProperty("datasource") String datasource,
        @JsonProperty("databaseId") String databaseId,
        @JsonProperty("engine") Engine engine,
        @JsonProperty("sqlHash") String sqlHash,
        @JsonProperty("sqlNormalized") String sqlNormalized,
        @JsonProperty("operation") SqlOperation operation,
        @JsonProperty("tables") List<TableAccess> tables,
        @JsonProperty("routines") List<RoutineRef> routines,
        @JsonProperty("columns") List<String> columns,
        @JsonProperty("defaultSchema") String defaultSchema,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("rows") long rows,
        @JsonProperty("success") boolean success,
        @JsonProperty("sqlState") String sqlState,
        @JsonProperty("errorCode") int errorCode,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("pinned") boolean pinned,
        @JsonProperty("poolMode") String poolMode,
        @JsonProperty("clientInfo") Map<String, String> clientInfo) {

    public static final int MAX_ERROR_MESSAGE = 500;
    public static final int MAX_SQL_NORMALIZED = 4000;

    public QueryEvent {
        tables = tables == null ? List.of() : List.copyOf(tables);
        routines = routines == null ? List.of() : List.copyOf(routines);
        columns = columns == null ? List.of() : List.copyOf(columns);
        errorMessage = truncate(errorMessage, MAX_ERROR_MESSAGE);
        sqlNormalized = truncate(sqlNormalized, MAX_SQL_NORMALIZED);
    }

    static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; defaults: {@code timestamp=now}, {@code rows=-1}, {@code success=true}. */
    public static final class Builder {
        private String eventId;
        private Instant timestamp = Instant.now();
        private String gatewayId;
        private String sessionId;
        private String applicationId;
        private String application;
        private String team;
        private String datasource;
        private String databaseId;
        private Engine engine;
        private String sqlHash;
        private String sqlNormalized;
        private SqlOperation operation = SqlOperation.OTHER;
        private List<TableAccess> tables = List.of();
        private List<RoutineRef> routines = List.of();
        private List<String> columns = List.of();
        private String defaultSchema;
        private long durationMs;
        private long rows = -1;
        private boolean success = true;
        private String sqlState;
        private int errorCode;
        private String errorMessage;
        private boolean pinned;
        private String poolMode;
        private Map<String, String> clientInfo;

        private Builder() {}

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder gatewayId(String v) { this.gatewayId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder applicationId(String v) { this.applicationId = v; return this; }
        public Builder application(String v) { this.application = v; return this; }
        public Builder team(String v) { this.team = v; return this; }
        public Builder datasource(String v) { this.datasource = v; return this; }
        public Builder databaseId(String v) { this.databaseId = v; return this; }
        public Builder engine(Engine v) { this.engine = v; return this; }
        public Builder sqlHash(String v) { this.sqlHash = v; return this; }
        public Builder sqlNormalized(String v) { this.sqlNormalized = v; return this; }
        public Builder operation(SqlOperation v) { this.operation = v; return this; }
        public Builder tables(List<TableAccess> v) { this.tables = v; return this; }
        public Builder routines(List<RoutineRef> v) { this.routines = v; return this; }
        public Builder columns(List<String> v) { this.columns = v; return this; }
        public Builder defaultSchema(String v) { this.defaultSchema = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder rows(long v) { this.rows = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder sqlState(String v) { this.sqlState = v; return this; }
        public Builder errorCode(int v) { this.errorCode = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder pinned(boolean v) { this.pinned = v; return this; }
        public Builder poolMode(String v) { this.poolMode = v; return this; }
        public Builder clientInfo(Map<String, String> v) { this.clientInfo = v; return this; }

        public QueryEvent build() {
            return new QueryEvent(eventId, timestamp, gatewayId, sessionId, applicationId, application, team,
                    datasource, databaseId, engine, sqlHash, sqlNormalized, operation, tables, routines, columns,
                    defaultSchema, durationMs, rows, success, sqlState, errorCode, errorMessage, pinned, poolMode,
                    clientInfo);
        }
    }
}
