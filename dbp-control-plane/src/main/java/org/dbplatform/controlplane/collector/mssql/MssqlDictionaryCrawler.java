package org.dbplatform.controlplane.collector.mssql;

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
 * SQL Server dictionary crawler: sys.tables / sys.views / sys.columns / sys.foreign_keys / sys.procedures /
 * sys.objects (FN, IF, TF) / sys.triggers / sys.sql_expression_dependencies / sys.sql_modules.
 */
@Component
public class MssqlDictionaryCrawler implements DictionaryCrawler {
    @Override public Enums.Engine engine() { return Enums.Engine.MSSQL; }

    @Override
    public CrawlResult crawl(Connection c, DatabaseInstance db, List<String> schemasIn) throws SQLException {
        CrawlResult out = new CrawlResult();
        List<Object> schemas = new ArrayList<>(schemasIn);
        if (schemas.isEmpty()) schemas.add("dbo");
        String in = Jdbc.placeholders(schemas.size());

        Jdbc.forEach(c, "SELECT s.name AS schema_name, t.name AS table_name, t.modify_date, (SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id = t.object_id AND p.index_id IN (0, 1)) AS row_count"
                + " FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name IN " + in, schemas, rs -> {
            TableInfo t = new TableInfo(rs.getString("schema_name"), rs.getString("table_name"), Enums.TableKind.TABLE);
            t.rowCount = Jdbc.longValue(rs, "row_count");
            t.lastDdl = Jdbc.instant(rs, "modify_date");
            out.tables.add(t);
        });
        Jdbc.forEach(c, "SELECT s.name AS schema_name, v.name AS view_name, v.modify_date, m.definition FROM sys.views v JOIN sys.schemas s ON s.schema_id = v.schema_id"
                + " LEFT JOIN sys.sql_modules m ON m.object_id = v.object_id WHERE s.name IN " + in, schemas, rs -> {
            TableInfo t = new TableInfo(rs.getString("schema_name"), rs.getString("view_name"), Enums.TableKind.VIEW);
            t.lastDdl = Jdbc.instant(rs, "modify_date");
            t.definition = rs.getString("definition");
            out.tables.add(t);
        });
        Jdbc.forEach(c, "SELECT s.name AS schema_name, o.name AS table_name, c.name AS column_name, c.column_id, ty.name AS type_name, c.max_length, c.precision, c.scale, c.is_nullable, dc.definition AS default_definition"
                + " FROM sys.columns c JOIN sys.objects o ON o.object_id = c.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id"
                + " JOIN sys.types ty ON ty.user_type_id = c.user_type_id LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id"
                + " WHERE o.type IN ('U', 'V') AND s.name IN " + in + " ORDER BY s.name, o.name, c.column_id", schemas, rs -> {
            TableInfo t = out.table(rs.getString("schema_name"), rs.getString("table_name"));
            if (t == null) return;
            Integer pos = Jdbc.integer(rs, "column_id");
            t.columns.add(new Model.ColumnInfo(rs.getString("column_name"), pos == null ? t.columns.size() + 1 : pos, rs.getString("type_name"), Jdbc.integer(rs, "max_length"),
                    Jdbc.integer(rs, "precision"), Jdbc.integer(rs, "scale"), rs.getBoolean("is_nullable"), rs.getString("default_definition"), null));
        });
        Jdbc.forEach(c, "SELECT ps.name AS parent_schema, pt.name AS parent_table, rs2.name AS ref_schema, rt.name AS ref_table FROM sys.foreign_keys fk"
                + " JOIN sys.tables pt ON pt.object_id = fk.parent_object_id JOIN sys.schemas ps ON ps.schema_id = pt.schema_id"
                + " JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id JOIN sys.schemas rs2 ON rs2.schema_id = rt.schema_id WHERE ps.name IN " + in, schemas, rs -> {
            DependencyInfo d = new DependencyInfo(new ObjRef(ObjectType.TABLE, rs.getString("parent_schema"), rs.getString("parent_table")),
                    new ObjRef(ObjectType.TABLE, rs.getString("ref_schema"), rs.getString("ref_table")), DependencyKind.FOREIGN_KEY, 1.0);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        });
        Jdbc.forEach(c, "SELECT s.name AS schema_name, o.name AS object_name, o.type, o.modify_date, m.definition FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id"
                + " LEFT JOIN sys.sql_modules m ON m.object_id = o.object_id WHERE o.type IN ('P', 'FN', 'IF', 'TF') AND s.name IN " + in, schemas, rs -> {
            String type = rs.getString("type").trim();
            RoutineInfo r = new RoutineInfo(rs.getString("schema_name"), rs.getString("object_name"), "P".equals(type) ? Enums.RoutineKind.PROCEDURE : Enums.RoutineKind.FUNCTION);
            r.lastDdl = Jdbc.instant(rs, "modify_date");
            r.source = rs.getString("definition");
            out.routines.add(r);
        });
        Jdbc.forEach(c, "SELECT s.name AS schema_name, tr.name AS trigger_name, t.name AS table_name, tr.is_disabled, tr.modify_date, m.definition,"
                + " (SELECT STRING_AGG(te.type_desc, ' OR ') FROM sys.trigger_events te WHERE te.object_id = tr.object_id) AS events"
                + " FROM sys.triggers tr JOIN sys.tables t ON t.object_id = tr.parent_id JOIN sys.schemas s ON s.schema_id = t.schema_id"
                + " LEFT JOIN sys.sql_modules m ON m.object_id = tr.object_id WHERE s.name IN " + in, schemas, rs -> {
            RoutineInfo r = new RoutineInfo(rs.getString("schema_name"), rs.getString("trigger_name"), Enums.RoutineKind.TRIGGER);
            r.triggerTableSchema = r.schema;
            r.triggerTableName = rs.getString("table_name");
            r.triggerEvent = rs.getString("events") + (rs.getBoolean("is_disabled") ? " (DISABLED)" : "");
            r.lastDdl = Jdbc.instant(rs, "modify_date");
            r.source = rs.getString("definition");
            out.routines.add(r);
            out.dependencies.add(new DependencyInfo(new ObjRef(ObjectType.TABLE, r.schema, r.triggerTableName), r.ref(), DependencyKind.TRIGGERS, 1.0));
        });
        Jdbc.forEach(c, "SELECT s.name AS schema_name, o.name AS object_name, o.type, d.referenced_schema_name, d.referenced_entity_name, ro.type AS referenced_type"
                + " FROM sys.sql_expression_dependencies d JOIN sys.objects o ON o.object_id = d.referencing_id JOIN sys.schemas s ON s.schema_id = o.schema_id"
                + " LEFT JOIN sys.objects ro ON ro.object_id = d.referenced_id WHERE d.referenced_entity_name IS NOT NULL AND s.name IN " + in, schemas, rs -> {
            String type = rs.getString("type").trim();
            String refType = rs.getString("referenced_type");
            String refSchema = rs.getString("referenced_schema_name");
            if (refSchema == null) refSchema = rs.getString("schema_name");
            ObjectType fromType = "V".equals(type) ? ObjectType.TABLE : ObjectType.ROUTINE;
            ObjectType toType;
            if (refType != null) toType = refType.trim().equals("U") || refType.trim().equals("V") ? ObjectType.TABLE : ObjectType.ROUTINE;
            else toType = out.table(refSchema, rs.getString("referenced_entity_name")) != null ? ObjectType.TABLE : ObjectType.ROUTINE;
            if (toType == ObjectType.ROUTINE && out.routine(refSchema, rs.getString("referenced_entity_name")) == null) return;
            if (toType == ObjectType.TABLE && out.table(refSchema, rs.getString("referenced_entity_name")) == null) return;
            if (fromType == ObjectType.TABLE ? out.table(rs.getString("schema_name"), rs.getString("object_name")) == null : out.routine(rs.getString("schema_name"), rs.getString("object_name")) == null) return;
            DependencyInfo d = new DependencyInfo(new ObjRef(fromType, rs.getString("schema_name"), rs.getString("object_name")),
                    new ObjRef(toType, refSchema, rs.getString("referenced_entity_name")), toType == ObjectType.ROUTINE ? DependencyKind.CALLS : DependencyKind.REFERENCES, 1.0);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        });
        for (RoutineInfo r : out.routines) if (r.source != null) refine(out, r, r.schema, r.source);
        for (TableInfo v : out.tables) {
            if (v.kind == Enums.TableKind.TABLE || v.definition == null) continue;
            for (SqlRefs.Ref ref : SqlRefs.extract(v.definition, Engine.MSSQL)) {
                TableInfo base = out.table(ref.schema() == null ? v.schema : ref.schema(), ref.name());
                if (base == null && ref.schema() == null) base = out.table("dbo", ref.name());
                if (base == null || base == v) continue;
                DependencyInfo d = new DependencyInfo(v.ref(), base.ref(), DependencyKind.REFERENCES, 1.0);
                if (!out.dependencies.contains(d)) out.dependencies.add(d);
            }
        }
        return out;
    }

    static void refine(CrawlResult out, RoutineInfo r, String defaultSchema, String text) {
        for (SqlRefs.Ref ref : SqlRefs.extract(text, Engine.MSSQL)) {
            TableInfo t = out.table(ref.schema() == null ? defaultSchema : ref.schema(), ref.name());
            if (t == null && ref.schema() == null) t = out.table("dbo", ref.name());
            if (t == null) continue;
            DependencyInfo d = new DependencyInfo(r.ref(), t.ref(), ref.write() ? DependencyKind.WRITES : DependencyKind.READS, 0.8);
            if (!out.dependencies.contains(d)) out.dependencies.add(d);
        }
    }
}
