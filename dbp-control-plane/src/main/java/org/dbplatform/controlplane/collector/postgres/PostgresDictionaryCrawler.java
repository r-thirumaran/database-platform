package org.dbplatform.controlplane.collector.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
 * PostgreSQL dictionary crawler, entirely on {@code pg_catalog} so that a least-privilege role (e.g. {@code pg_monitor}
 * without any table grant) sees every object: pg_class + pg_namespace (relkind r/p = TABLE, v = VIEW, m = MATERIALIZED_VIEW),
 * pg_attribute + pg_attrdef (columns, types through format_type, defaults through pg_get_expr), pg_matviews / pg_views
 * (definitions, parsed → view REFERENCES base tables), pg_stat_user_tables (row estimates), pg_description (comments),
 * pg_constraint (FKs), pg_proc (functions/procedures; prosrc parsed for table references), pg_trigger.
 * {@code information_schema} is deliberately not used: it only lists objects the connected role holds privileges on.
 */
@Component
public class PostgresDictionaryCrawler implements DictionaryCrawler {
    @Override public Enums.Engine engine() { return Enums.Engine.POSTGRES; }

    @Override
    public CrawlResult crawl(Connection c, DatabaseInstance db, List<String> schemasIn) throws SQLException {
        CrawlResult out = new CrawlResult();
        List<Object> schemas = new ArrayList<>(schemasIn);
        if (schemas.isEmpty()) schemas.add("public");
        String in = Jdbc.placeholders(schemas.size());

        // Everything comes from pg_catalog, which is readable by any role. information_schema.tables/columns only list the
        // objects the connected role holds privileges on, so a pg_monitor-only collector role would see (almost) nothing.
        Jdbc.forEach(c, "SELECT n.nspname, c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relkind IN ('r', 'p', 'v', 'm') AND n.nspname IN " + in + " ORDER BY n.nspname, c.relname", schemas, rs -> {
            out.tables.add(new TableInfo(rs.getString("nspname"), rs.getString("relname"), kindOf(rs.getString("relkind"))));
        });
        Jdbc.forEach(c, "SELECT schemaname, matviewname, definition FROM pg_matviews WHERE schemaname IN " + in, schemas, rs -> {
            TableInfo t = out.table(rs.getString("schemaname"), rs.getString("matviewname"));
            if (t != null) t.definition = rs.getString("definition");
        });
        Jdbc.forEach(c, "SELECT schemaname, relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname IN " + in, schemas, rs -> {
            TableInfo t = out.table(rs.getString("schemaname"), rs.getString("relname"));
            if (t != null) t.rowCount = Jdbc.longValue(rs, "n_live_tup");
        });
        // columns of tables, views and materialized views; length/precision/scale are decoded from atttypmod
        // (1042 bpchar, 1043 varchar: typmod = length + 4; 1700 numeric: typmod = ((precision << 16) | scale) + 4)
        Jdbc.forEach(c, "SELECT n.nspname, c.relname, a.attname, a.attnum, format_type(a.atttypid, a.atttypmod) AS data_type,"
                + " CASE WHEN a.atttypid IN (1042, 1043) AND a.atttypmod > 4 THEN a.atttypmod - 4 END AS char_length,"
                + " CASE WHEN a.atttypid = 1700 AND a.atttypmod >= 4 THEN ((a.atttypmod - 4) >> 16) & 65535 END AS num_precision,"
                + " CASE WHEN a.atttypid = 1700 AND a.atttypmod >= 4 THEN (a.atttypmod - 4) & 65535 END AS num_scale,"
                + " NOT a.attnotnull AS nullable, pg_get_expr(d.adbin, d.adrelid) AS column_default"
                + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
                + " WHERE c.relkind IN ('r', 'p', 'v', 'm') AND a.attnum > 0 AND NOT a.attisdropped AND n.nspname IN " + in
                + " ORDER BY n.nspname, c.relname, a.attnum", schemas, rs -> {
            TableInfo t = out.table(rs.getString("nspname"), rs.getString("relname"));
            if (t == null) return;
            Integer pos = Jdbc.integer(rs, "attnum");
            t.columns.add(new Model.ColumnInfo(rs.getString("attname"), pos == null ? t.columns.size() + 1 : pos, rs.getString("data_type"),
                    Jdbc.integer(rs, "char_length"), Jdbc.integer(rs, "num_precision"), Jdbc.integer(rs, "num_scale"),
                    rs.getBoolean("nullable"), rs.getString("column_default"), null));
        });
        Jdbc.forEach(c, "SELECT n.nspname, c.relname, d.objsubid, d.description FROM pg_description d JOIN pg_class c ON c.oid = d.objoid"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE d.classoid = 'pg_class'::regclass AND c.relkind IN ('r', 'p', 'v', 'm') AND n.nspname IN " + in, schemas, rs -> {
            TableInfo t = out.table(rs.getString("nspname"), rs.getString("relname"));
            if (t == null) return;
            Integer sub = Jdbc.integer(rs, "objsubid");
            String desc = rs.getString("description");
            if (sub == null || sub == 0) { t.comment = desc; return; }
            for (int i = 0; i < t.columns.size(); i++) {
                Model.ColumnInfo ci = t.columns.get(i);
                if (ci.position() == sub) t.columns.set(i, new Model.ColumnInfo(ci.name(), ci.position(), ci.dataType(), ci.length(), ci.precision(), ci.scale(), ci.nullable(), ci.defaultValue(), desc));
            }
        });
        Jdbc.forEach(c, "SELECT n.nspname, c.relname, rn.nspname AS ref_schema, rc.relname AS ref_table FROM pg_constraint k"
                + " JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " JOIN pg_class rc ON rc.oid = k.confrelid JOIN pg_namespace rn ON rn.oid = rc.relnamespace"
                + " WHERE k.contype = 'f' AND n.nspname IN " + in, schemas, rs -> {
            DependencyInfo d = new DependencyInfo(new ObjRef(ObjectType.TABLE, rs.getString("nspname"), rs.getString("relname")),
                    new ObjRef(ObjectType.TABLE, rs.getString("ref_schema"), rs.getString("ref_table")), DependencyKind.FOREIGN_KEY, 1.0);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        });
        Jdbc.forEach(c, "SELECT n.nspname, p.proname, p.prokind, p.prosrc, l.lanname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " JOIN pg_language l ON l.oid = p.prolang WHERE p.prokind IN ('f', 'p') AND n.nspname IN " + in, schemas, rs -> {
            String schema = rs.getString("nspname"), name = rs.getString("proname");
            if (out.routine(schema, name) != null) return; // overloads collapse into one routine
            RoutineInfo r = new RoutineInfo(schema, name, "p".equals(rs.getString("prokind")) ? Enums.RoutineKind.PROCEDURE : Enums.RoutineKind.FUNCTION);
            r.source = rs.getString("prosrc");
            out.routines.add(r);
            String lang = rs.getString("lanname");
            if (r.source != null && (lang == null || lang.equalsIgnoreCase("sql") || lang.equalsIgnoreCase("plpgsql"))) refine(out, r, schema, r.source);
        });
        Jdbc.forEach(c, "SELECT n.nspname, t.tgname, c.relname, t.tgtype, t.tgenabled, p.proname, pn.nspname AS proc_schema FROM pg_trigger t"
                + " JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " JOIN pg_proc p ON p.oid = t.tgfoid JOIN pg_namespace pn ON pn.oid = p.pronamespace"
                + " WHERE NOT t.tgisinternal AND n.nspname IN " + in, schemas, rs -> {
            String schema = rs.getString("nspname"), name = rs.getString("tgname");
            RoutineInfo r = out.routine(schema, name);
            if (r == null) { r = new RoutineInfo(schema, name, Enums.RoutineKind.TRIGGER); out.routines.add(r); }
            r.triggerTableSchema = schema;
            r.triggerTableName = rs.getString("relname");
            Integer type = Jdbc.integer(rs, "tgtype");
            r.triggerEvent = type == null ? null : triggerEvent(type);
            if ("D".equals(rs.getString("tgenabled"))) r.triggerEvent = r.triggerEvent + " (DISABLED)";
            out.dependencies.add(new DependencyInfo(new ObjRef(ObjectType.TABLE, schema, r.triggerTableName), r.ref(), DependencyKind.TRIGGERS, 1.0));
            String fn = rs.getString("proname"), fnSchema = rs.getString("proc_schema");
            if (fn != null && out.routine(fnSchema, fn) != null) {
                out.dependencies.add(new DependencyInfo(r.ref(), new ObjRef(ObjectType.ROUTINE, fnSchema, fn), DependencyKind.CALLS, 1.0));
            }
        });
        Jdbc.forEach(c, "SELECT schemaname, viewname, definition FROM pg_views WHERE schemaname IN " + in, schemas, rs -> {
            TableInfo v = out.table(rs.getString("schemaname"), rs.getString("viewname"));
            if (v != null) v.definition = rs.getString("definition");
        });
        for (TableInfo v : out.tables) {
            if (v.kind == Enums.TableKind.TABLE || v.definition == null) continue;
            for (SqlRefs.Ref ref : SqlRefs.extract(v.definition, Engine.POSTGRES)) {
                TableInfo base = out.table(ref.schema() == null ? v.schema : ref.schema(), ref.name());
                if (base == null || base == v) continue;
                DependencyInfo d = new DependencyInfo(v.ref(), base.ref(), DependencyKind.REFERENCES, 1.0);
                if (!out.dependencies.contains(d)) out.dependencies.add(d);
            }
        }
        return out;
    }

    /** pg_class.relkind: r (table) and p (partitioned table) are tables, v a view, m a materialized view. */
    static Enums.TableKind kindOf(String relkind) {
        return switch (relkind == null ? "" : relkind) {
            case "v" -> Enums.TableKind.VIEW;
            case "m" -> Enums.TableKind.MATERIALIZED_VIEW;
            default -> Enums.TableKind.TABLE;
        };
    }

    public static String triggerEvent(int tgtype) {
        List<String> ev = new ArrayList<>();
        if ((tgtype & 4) != 0) ev.add("INSERT");
        if ((tgtype & 8) != 0) ev.add("DELETE");
        if ((tgtype & 16) != 0) ev.add("UPDATE");
        if ((tgtype & 32) != 0) ev.add("TRUNCATE");
        String when = (tgtype & 2) != 0 ? "BEFORE" : (tgtype & 64) != 0 ? "INSTEAD OF" : "AFTER";
        return when + " " + String.join(" OR ", ev);
    }

    static void refine(CrawlResult out, RoutineInfo r, String defaultSchema, String text) {
        for (SqlRefs.Ref ref : SqlRefs.extract(text, Engine.POSTGRES)) {
            TableInfo t = out.table(ref.schema() == null ? defaultSchema : ref.schema(), ref.name());
            if (t == null && ref.schema() == null) t = out.table("public", ref.name());
            if (t == null) continue;
            DependencyInfo refs = new DependencyInfo(r.ref(), t.ref(), DependencyKind.REFERENCES, 1.0);
            if (!out.dependencies.contains(refs)) out.dependencies.add(refs);
            DependencyInfo d = new DependencyInfo(r.ref(), t.ref(), ref.write() ? DependencyKind.WRITES : DependencyKind.READS, 0.8);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        }
    }
}
