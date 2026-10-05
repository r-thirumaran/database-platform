package org.dbplatform.controlplane.collector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.dbplatform.controlplane.domain.Enums;

/** Engine-neutral results produced by the crawlers / samplers and applied by the mergers. */
public final class Model {
    private Model() {}

    public record ObjRef(Enums.ObjectType type, String schema, String name) {
        public String key() { return type + ":" + schema.toUpperCase() + "." + name.toUpperCase(); }
    }

    public record ColumnInfo(String name, int position, String dataType, Integer length, Integer precision, Integer scale, boolean nullable, String defaultValue, String comment) {}

    public static final class TableInfo {
        public final String schema, name;
        public final Enums.TableKind kind;
        public Long rowCount;
        public Instant lastDdl;
        public String comment;
        public String definition;              // view definition text when available
        public final List<ColumnInfo> columns = new ArrayList<>();
        public TableInfo(String schema, String name, Enums.TableKind kind) { this.schema = schema; this.name = name; this.kind = kind; }
        public ObjRef ref() { return new ObjRef(Enums.ObjectType.TABLE, schema, name); }
    }

    public static final class RoutineInfo {
        public final String schema, name;
        public final Enums.RoutineKind kind;
        public String triggerTableSchema, triggerTableName, triggerEvent;
        public Enums.RoutineStatus status = Enums.RoutineStatus.VALID;
        public Instant lastDdl;
        public String source;                  // body text when available (for READ/WRITE refinement)
        public RoutineInfo(String schema, String name, Enums.RoutineKind kind) { this.schema = schema; this.name = name; this.kind = kind; }
        public ObjRef ref() { return new ObjRef(Enums.ObjectType.ROUTINE, schema, name); }
    }

    public record DependencyInfo(ObjRef from, ObjRef to, Enums.DependencyKind kind, double confidence) {}

    public static final class CrawlResult {
        public final List<TableInfo> tables = new ArrayList<>();
        public final List<RoutineInfo> routines = new ArrayList<>();
        public final List<DependencyInfo> dependencies = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public TableInfo table(String schema, String name) {
            for (TableInfo t : tables) if (t.schema.equalsIgnoreCase(schema) && t.name.equalsIgnoreCase(name)) return t;
            return null;
        }
        public RoutineInfo routine(String schema, String name) {
            for (RoutineInfo r : routines) if (r.schema.equalsIgnoreCase(schema) && r.name.equalsIgnoreCase(name)) return r;
            return null;
        }
    }

    /** One database session as seen by the engine's session view. */
    public static final class SessionInfo {
        public String sessionId;               // SID,SERIAL# / pid / session_id
        public String dbUser, status, program, module, action, clientIdentifier, machine, osUser, clientAddr, serviceName;
        public Integer port;                   // client port as seen by the database (joins proxyLocalPort)
        public Instant logonTime, sqlExecStart;
        public String sqlId, prevSqlId, sqlText;
        public String key() { return sessionId + "|" + sqlId; }
    }

    /** A statement's text and the tables it touches (from the plan or from SQL analysis). */
    public static final class SqlInfo {
        public String sqlId, sqlText, module, parsingSchema;
        public long executions, elapsedMicros, rowsProcessed;
        public Instant lastActive;
        public final List<TableTouch> tables = new ArrayList<>();
    }

    public record TableTouch(String schema, String name, boolean write) {}

    public static final class RuntimeSample {
        public final List<SessionInfo> sessions = new ArrayList<>();
        public final List<SqlInfo> statements = new ArrayList<>();   // only statements not already known
        public final List<String> warnings = new ArrayList<>();
    }

    public record AuditRow(Instant at, String dbUser, String program, String host, String osUser, String action, String objectSchema, String objectName, String sqlText, String sessionId) {}

    public static final class AuditBatch {
        public final List<AuditRow> rows = new ArrayList<>();
        public Instant cursor;
    }
}
