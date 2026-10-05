package org.dbplatform.controlplane.collector.oracle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.controlplane.collector.DictionaryCrawler;
import org.dbplatform.controlplane.collector.Jdbc;
import org.dbplatform.controlplane.collector.Model;
import org.dbplatform.controlplane.collector.Model.CrawlResult;
import org.dbplatform.controlplane.collector.Model.DependencyInfo;
import org.dbplatform.controlplane.collector.Model.ObjRef;
import org.dbplatform.controlplane.collector.Model.RoutineInfo;
import org.dbplatform.controlplane.collector.Model.TableInfo;
import org.dbplatform.controlplane.collector.SqlRefs;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.DependencyKind;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.springframework.stereotype.Component;

/**
 * Oracle data dictionary crawler. Queries the DBA_* views first (a collector account with
 * SELECT_CATALOG_ROLE sees every object) and falls back to the ALL_* equivalents on ORA-00942
 * (ALL_* only shows objects the account has privileges on, so coverage may be partial).
 * Only licence-free dictionary views are used: no ASH/AWR/DBA_HIST_* (Diagnostics Pack).
 * Package members come from DBA_PROCEDURES; member-level READ/WRITE dependencies are a best-effort
 * refinement from the package body text in DBA_SOURCE (dictionary dependencies are package-level).
 */
@Component
public class OracleDictionaryCrawler implements DictionaryCrawler {
    static final int MAX_SOURCE_LINES = 400_000;

    @Override public Enums.Engine engine() { return Enums.Engine.ORACLE; }

    @Override
    public CrawlResult crawl(Connection c, DatabaseInstance db, List<String> schemasIn) throws SQLException {
        CrawlResult out = new CrawlResult();
        List<Object> schemas = new ArrayList<>();
        for (String s : schemasIn) schemas.add(s.toUpperCase(Locale.ROOT));
        if (schemas.isEmpty()) { out.warnings.add("collector.schemas is empty: nothing crawled (set the schemas to crawl on the database)"); return out; }
        String in = Jdbc.placeholders(schemas.size());

        // tables, views, materialized views
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, TABLE_NAME, NUM_ROWS, LAST_ANALYZED FROM DBA_TABLES WHERE OWNER IN " + in + " AND NESTED = 'NO' AND (IOT_TYPE IS NULL OR IOT_TYPE <> 'IOT_OVERFLOW')", schemas, rs -> {
            TableInfo t = new TableInfo(rs.getString("OWNER"), rs.getString("TABLE_NAME"), Enums.TableKind.TABLE);
            t.rowCount = Jdbc.longValue(rs, "NUM_ROWS");
            out.tables.add(t);
        });
        Map<String, TableInfo> mviews = new LinkedHashMap<>();
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, MVIEW_NAME FROM DBA_MVIEWS WHERE OWNER IN " + in, schemas, rs -> {
            TableInfo existing = out.table(rs.getString("OWNER"), rs.getString("MVIEW_NAME"));
            if (existing != null) out.tables.remove(existing);
            TableInfo t = new TableInfo(rs.getString("OWNER"), rs.getString("MVIEW_NAME"), Enums.TableKind.MATERIALIZED_VIEW);
            if (existing != null) t.rowCount = existing.rowCount;
            out.tables.add(t);
            mviews.put(t.ref().key(), t);
        });
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, VIEW_NAME, TEXT FROM DBA_VIEWS WHERE OWNER IN " + in, schemas, rs -> {
            TableInfo t = new TableInfo(rs.getString("OWNER"), rs.getString("VIEW_NAME"), Enums.TableKind.VIEW);
            t.definition = Jdbc.string(rs, "TEXT");
            out.tables.add(t);
        });
        // columns
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, TABLE_NAME, COLUMN_NAME, COLUMN_ID, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE, DATA_DEFAULT"
                + " FROM DBA_TAB_COLUMNS WHERE OWNER IN " + in + " ORDER BY OWNER, TABLE_NAME, COLUMN_ID", schemas, rs -> {
            TableInfo t = out.table(rs.getString("OWNER"), rs.getString("TABLE_NAME"));
            if (t == null) return;
            Integer id = Jdbc.integer(rs, "COLUMN_ID");
            String def = Jdbc.string(rs, "DATA_DEFAULT");
            t.columns.add(new Model.ColumnInfo(rs.getString("COLUMN_NAME"), id == null ? t.columns.size() + 1 : id, rs.getString("DATA_TYPE"),
                    Jdbc.integer(rs, "DATA_LENGTH"), Jdbc.integer(rs, "DATA_PRECISION"), Jdbc.integer(rs, "DATA_SCALE"), !"N".equals(rs.getString("NULLABLE")),
                    def == null ? null : def.trim(), null));
        });
        // comments
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, TABLE_NAME, COMMENTS FROM DBA_TAB_COMMENTS WHERE OWNER IN " + in + " AND COMMENTS IS NOT NULL", schemas, rs -> {
            TableInfo t = out.table(rs.getString("OWNER"), rs.getString("TABLE_NAME"));
            if (t != null) t.comment = rs.getString("COMMENTS");
        });
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, TABLE_NAME, COLUMN_NAME, COMMENTS FROM DBA_COL_COMMENTS WHERE OWNER IN " + in + " AND COMMENTS IS NOT NULL", schemas, rs -> {
            TableInfo t = out.table(rs.getString("OWNER"), rs.getString("TABLE_NAME"));
            if (t == null) return;
            String col = rs.getString("COLUMN_NAME"), comment = rs.getString("COMMENTS");
            for (int i = 0; i < t.columns.size(); i++) {
                Model.ColumnInfo ci = t.columns.get(i);
                if (ci.name().equalsIgnoreCase(col)) t.columns.set(i, new Model.ColumnInfo(ci.name(), ci.position(), ci.dataType(), ci.length(), ci.precision(), ci.scale(), ci.nullable(), ci.defaultValue(), comment));
            }
        });
        // routines: packages, procedures, functions, triggers (package bodies merged into the package)
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS, LAST_DDL_TIME FROM DBA_OBJECTS WHERE OWNER IN " + in
                + " AND OBJECT_TYPE IN ('PACKAGE', 'PACKAGE BODY', 'PROCEDURE', 'FUNCTION', 'TRIGGER')", schemas, rs -> {
            String type = rs.getString("OBJECT_TYPE");
            String owner = rs.getString("OWNER"), name = rs.getString("OBJECT_NAME");
            Enums.RoutineKind kind = switch (type) {
                case "PACKAGE", "PACKAGE BODY" -> Enums.RoutineKind.PACKAGE;
                case "PROCEDURE" -> Enums.RoutineKind.PROCEDURE;
                case "FUNCTION" -> Enums.RoutineKind.FUNCTION;
                default -> Enums.RoutineKind.TRIGGER;
            };
            RoutineInfo r = out.routine(owner, name);
            if (r == null) { r = new RoutineInfo(owner, name, kind); out.routines.add(r); }
            if ("INVALID".equalsIgnoreCase(rs.getString("STATUS"))) r.status = Enums.RoutineStatus.INVALID;
            var ddl = Jdbc.instant(rs, "LAST_DDL_TIME");
            if (ddl != null && (r.lastDdl == null || ddl.isAfter(r.lastDdl))) r.lastDdl = ddl;
        });
        // package members → ROUTINE "PKG.MEMBER"; package REFERENCES member
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, OBJECT_NAME, PROCEDURE_NAME FROM DBA_PROCEDURES WHERE OWNER IN " + in
                + " AND OBJECT_TYPE = 'PACKAGE' AND PROCEDURE_NAME IS NOT NULL", schemas, rs -> {
            String owner = rs.getString("OWNER"), pkg = rs.getString("OBJECT_NAME"), member = rs.getString("PROCEDURE_NAME");
            String full = pkg + "." + member;
            if (out.routine(owner, full) == null) {
                RoutineInfo r = new RoutineInfo(owner, full, Enums.RoutineKind.PROCEDURE);
                RoutineInfo p = out.routine(owner, pkg);
                if (p != null) { r.lastDdl = p.lastDdl; r.status = p.status; }
                out.routines.add(r);
                out.dependencies.add(new DependencyInfo(new ObjRef(ObjectType.ROUTINE, owner, pkg), r.ref(), DependencyKind.REFERENCES, 1.0));
            }
        });
        // triggers
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, TRIGGER_NAME, TRIGGERING_EVENT, TABLE_OWNER, TABLE_NAME, STATUS FROM DBA_TRIGGERS WHERE OWNER IN " + in, schemas, rs -> {
            String owner = rs.getString("OWNER"), name = rs.getString("TRIGGER_NAME");
            RoutineInfo r = out.routine(owner, name);
            if (r == null) { r = new RoutineInfo(owner, name, Enums.RoutineKind.TRIGGER); out.routines.add(r); }
            r.triggerTableSchema = rs.getString("TABLE_OWNER");
            r.triggerTableName = rs.getString("TABLE_NAME");
            String ev = rs.getString("TRIGGERING_EVENT");
            r.triggerEvent = ev == null ? null : ev.trim();
            if ("DISABLED".equalsIgnoreCase(rs.getString("STATUS")) && r.triggerEvent != null) r.triggerEvent = r.triggerEvent + " (DISABLED)";
            if (r.triggerTableName != null) {
                out.dependencies.add(new DependencyInfo(new ObjRef(ObjectType.TABLE, r.triggerTableSchema, r.triggerTableName), r.ref(), DependencyKind.TRIGGERS, 1.0));
            }
        });
        // dictionary dependencies (routine / trigger / view → table / view / routine)
        Jdbc.forEachDbaOrAll(c, "SELECT OWNER, NAME, TYPE, REFERENCED_OWNER, REFERENCED_NAME, REFERENCED_TYPE FROM DBA_DEPENDENCIES WHERE OWNER IN " + in
                + " AND TYPE IN ('PACKAGE', 'PACKAGE BODY', 'PROCEDURE', 'FUNCTION', 'TRIGGER', 'VIEW', 'MATERIALIZED VIEW')"
                + " AND REFERENCED_TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'PACKAGE', 'PROCEDURE', 'FUNCTION')", schemas, rs -> {
            String type = rs.getString("TYPE"), refType = rs.getString("REFERENCED_TYPE");
            ObjectType fromType = type.equals("VIEW") || type.equals("MATERIALIZED VIEW") ? ObjectType.TABLE : ObjectType.ROUTINE;
            ObjectType toType = refType.equals("TABLE") || refType.equals("VIEW") || refType.equals("MATERIALIZED VIEW") ? ObjectType.TABLE : ObjectType.ROUTINE;
            ObjRef from = new ObjRef(fromType, rs.getString("OWNER"), rs.getString("NAME"));
            ObjRef to = new ObjRef(toType, rs.getString("REFERENCED_OWNER"), rs.getString("REFERENCED_NAME"));
            if (from.key().equals(to.key())) return; // package spec ↔ body
            DependencyKind kind = toType == ObjectType.ROUTINE ? DependencyKind.CALLS : DependencyKind.REFERENCES;
            DependencyInfo d = new DependencyInfo(from, to, kind, 1.0);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        });
        // foreign keys
        Jdbc.forEachDbaOrAll(c, "SELECT c.OWNER, c.TABLE_NAME, r.OWNER AS R_OWNER, r.TABLE_NAME AS R_TABLE_NAME FROM DBA_CONSTRAINTS c"
                + " JOIN DBA_CONSTRAINTS r ON r.OWNER = c.R_OWNER AND r.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME"
                + " WHERE c.CONSTRAINT_TYPE = 'R' AND c.OWNER IN " + in, schemas, rs -> {
            DependencyInfo d = new DependencyInfo(new ObjRef(ObjectType.TABLE, rs.getString("OWNER"), rs.getString("TABLE_NAME")),
                    new ObjRef(ObjectType.TABLE, rs.getString("R_OWNER"), rs.getString("R_TABLE_NAME")), DependencyKind.FOREIGN_KEY, 1.0);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        });
        // source text (best effort READ/WRITE refinement and package member attribution)
        Map<String, StringBuilder> sources = new LinkedHashMap<>();
        int[] lines = {0};
        try {
            Jdbc.forEachDbaOrAll(c, "SELECT OWNER, NAME, TYPE, LINE, TEXT FROM DBA_SOURCE WHERE OWNER IN " + in
                    + " AND TYPE IN ('PACKAGE BODY', 'PROCEDURE', 'FUNCTION', 'TRIGGER') ORDER BY OWNER, NAME, TYPE, LINE", schemas, rs -> {
                if (lines[0]++ > MAX_SOURCE_LINES) return;
                String key = rs.getString("OWNER") + "|" + rs.getString("NAME") + "|" + rs.getString("TYPE");
                String text = rs.getString("TEXT");
                if (text != null) sources.computeIfAbsent(key, k -> new StringBuilder()).append(text);
            });
        } catch (SQLException e) {
            out.warnings.add("DBA_SOURCE/ALL_SOURCE not readable (" + e.getMessage() + "): READ/WRITE refinement skipped");
        }
        if (lines[0] > MAX_SOURCE_LINES) out.warnings.add("source text truncated after " + MAX_SOURCE_LINES + " lines");
        for (Map.Entry<String, StringBuilder> e : sources.entrySet()) {
            String[] k = e.getKey().split("\\|");
            String owner = k[0], name = k[1], type = k[2];
            RoutineInfo r = out.routine(owner, name);
            if (r == null) continue;
            String text = e.getValue().toString();
            r.source = text.length() > 200_000 ? text.substring(0, 200_000) : text;
            if (type.equals("PACKAGE BODY")) {
                for (Map.Entry<String, String> m : SqlRefs.splitPackageMembers(text).entrySet()) {
                    RoutineInfo member = out.routine(owner, name + "." + m.getKey());
                    if (member == null) continue;
                    member.source = m.getValue();
                    refine(out, member, owner, m.getValue());
                }
            }
            refine(out, r, owner, text);
        }
        // view definitions → view REFERENCES base tables (when the dictionary did not already say so)
        for (TableInfo v : out.tables) {
            if (v.kind == Enums.TableKind.TABLE || v.definition == null) continue;
            for (SqlRefs.Ref ref : SqlRefs.extract(v.definition, Engine.ORACLE)) {
                TableInfo base = out.table(ref.schema() == null ? v.schema : ref.schema(), ref.name());
                if (base == null || base == v) continue;
                DependencyInfo d = new DependencyInfo(v.ref(), base.ref(), DependencyKind.REFERENCES, 0.8);
                boolean known = out.dependencies.stream().anyMatch(x -> x.from().key().equals(d.from().key()) && x.to().key().equals(d.to().key()));
                if (!known) out.dependencies.add(d);
            }
        }
        return out;
    }

    /** Adds READS/WRITES edges (confidence 0.8) for tables the routine text conclusively reads or writes. */
    static void refine(CrawlResult out, RoutineInfo r, String defaultSchema, String text) {
        for (SqlRefs.Ref ref : SqlRefs.extract(text, Engine.ORACLE)) {
            TableInfo t = out.table(ref.schema() == null ? defaultSchema : ref.schema(), ref.name());
            if (t == null) continue;
            DependencyKind kind = ref.write() ? DependencyKind.WRITES : DependencyKind.READS;
            DependencyInfo d = new DependencyInfo(r.ref(), t.ref(), kind, 0.8);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        }
    }
}
