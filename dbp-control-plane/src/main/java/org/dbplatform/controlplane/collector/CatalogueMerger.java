package org.dbplatform.controlplane.collector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.DbColumn;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.DbColumnRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.DependencyRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.service.CatalogueService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies a {@link Model.CrawlResult} to the catalogue (upsert tables/columns/routines, refresh DICTIONARY dependencies). */
@Component
public class CatalogueMerger {
    private final DbTableRepository tables;
    private final DbColumnRepository columns;
    private final RoutineRepository routines;
    private final DependencyRepository dependencies;
    private final CatalogueService catalogue;

    public CatalogueMerger(DbTableRepository tables, DbColumnRepository columns, RoutineRepository routines, DependencyRepository dependencies, CatalogueService catalogue) {
        this.tables = tables; this.columns = columns; this.routines = routines; this.dependencies = dependencies; this.catalogue = catalogue;
    }

    public record MergeStats(int tables, int routines, int dependencies, int removedDependencies) {}

    @Transactional
    public MergeStats apply(DatabaseInstance db, Model.CrawlResult result) {
        Instant now = Instant.now();
        Map<String, String> idsByKey = new HashMap<>();
        for (Model.TableInfo ti : result.tables) {
            DbTable t = tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), ti.schema, ti.name).orElseGet(() -> {
                DbTable n = new DbTable();
                n.setId(Ids.newId()); n.setDatabaseId(db.getId()); n.setSchema(ti.schema); n.setName(ti.name); n.setFirstSeenAt(now);
                return n;
            });
            t.setKind(ti.kind);
            t.setDiscovered(false);
            if (ti.rowCount != null) t.setRowCountEstimate(ti.rowCount);
            if (ti.lastDdl != null) t.setLastDdlAt(ti.lastDdl);
            if (t.getDescription() == null && ti.comment != null && !ti.comment.isBlank()) t.setDescription(ti.comment);
            t.setLastSeenAt(now);
            t = tables.save(t);
            idsByKey.put(ti.ref().key(), t.getId());
            mergeColumns(t, ti.columns);
        }
        for (Model.RoutineInfo ri : result.routines) {
            Routine r = routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), ri.schema, ri.name).orElseGet(() -> {
                Routine n = new Routine();
                n.setId(Ids.newId()); n.setDatabaseId(db.getId()); n.setSchema(ri.schema); n.setName(ri.name); n.setFirstSeenAt(now);
                return n;
            });
            r.setKind(ri.kind);
            r.setStatus(ri.status);
            r.setDiscovered(false);
            if (ri.lastDdl != null) r.setLastDdlAt(ri.lastDdl);
            r.setTriggerEvent(ri.triggerEvent);
            if (ri.triggerTableName != null) {
                String key = new Model.ObjRef(ObjectType.TABLE, ri.triggerTableSchema == null ? ri.schema : ri.triggerTableSchema, ri.triggerTableName).key();
                String tid = idsByKey.get(key);
                if (tid == null) tid = tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), ri.triggerTableSchema == null ? ri.schema : ri.triggerTableSchema, ri.triggerTableName).map(DbTable::getId).orElse(null);
                r.setTriggerTableId(tid);
            }
            r.setLastSeenAt(now);
            r = routines.save(r);
            idsByKey.put(ri.ref().key(), r.getId());
        }
        // dependencies: upsert the fresh set, then drop DICTIONARY rows from crawled objects that vanished
        Set<String> freshKeys = new HashSet<>();
        int applied = 0;
        for (Model.DependencyInfo d : result.dependencies) {
            String from = resolve(db, d.from(), idsByKey);
            String to = resolve(db, d.to(), idsByKey);
            if (from == null || to == null || from.equals(to)) continue;
            Dependency dep = catalogue.upsertDependency(d.from().type(), from, d.to().type(), to, d.kind(), Enums.DependencySource.DICTIONARY, d.confidence());
            freshKeys.add(dep.getId());
            applied++;
        }
        int removed = 0;
        List<String> crawledIds = new ArrayList<>(idsByKey.values());
        if (!crawledIds.isEmpty()) {
            for (Dependency old : dependencies.findBySourceAndFromIdIn(Enums.DependencySource.DICTIONARY, crawledIds)) {
                if (!freshKeys.contains(old.getId())) { dependencies.delete(old); removed++; }
            }
        }
        return new MergeStats(result.tables.size(), result.routines.size(), applied, removed);
    }

    private String resolve(DatabaseInstance db, Model.ObjRef ref, Map<String, String> idsByKey) {
        String id = idsByKey.get(ref.key());
        if (id != null) return id;
        if (ref.type() == ObjectType.TABLE) {
            return tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), ref.schema(), ref.name()).map(DbTable::getId).orElse(null);
        }
        return routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), ref.schema(), ref.name()).map(Routine::getId).orElse(null);
    }

    private void mergeColumns(DbTable t, List<Model.ColumnInfo> cols) {
        if (cols.isEmpty()) return;
        Map<String, DbColumn> existing = new HashMap<>();
        for (DbColumn c : columns.findByTableIdOrderByPositionAsc(t.getId())) existing.put(c.getName().toUpperCase(), c);
        for (Model.ColumnInfo ci : cols) {
            DbColumn c = existing.remove(ci.name().toUpperCase());
            if (c == null) { c = new DbColumn(); c.setId(Ids.newId()); c.setTableId(t.getId()); c.setName(ci.name()); }
            c.setPosition(ci.position()); c.setDataType(ci.dataType()); c.setLength(ci.length()); c.setPrecision(ci.precision()); c.setScale(ci.scale());
            c.setNullable(ci.nullable()); c.setDefaultValue(ci.defaultValue());
            if (c.getComment() == null && ci.comment() != null) c.setComment(ci.comment());
            columns.save(c);
        }
        existing.values().forEach(columns::delete);
    }
}
