package org.dbplatform.controlplane.service.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.QueryStat;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.QueryStatRepository;
import org.dbplatform.controlplane.service.CatalogueService;

/**
 * The updates of the <em>shared</em> rows an ingestion chunk touches: catalogue tables and routines (last seen), relationships (query count),
 * hourly query statistics. Every event of a busy platform touches the same few of those rows, and a managed entity that is modified when the
 * event is processed is written (and its row locked) at the next flush and stays locked until the chunk commits. So the events only
 * <em>record</em> their effect here, the effects are folded per row (the result is exactly that of applying the events one after the other), and
 * {@link #apply} writes every shared row once, in a fixed order, right before the commit: row locks are held for the commit window instead of
 * the whole chunk, and concurrent chunks always take them in the same order (no deadlocks between them).
 */
final class ChunkWrites {
    private static final int RESERVOIR = 100;

    private final QueryStatRepository stats;
    private final Map<String, TableSeen> tables = new TreeMap<>();
    private final Map<String, RoutineSeen> routines = new TreeMap<>();
    private final Map<String, RelationshipHits> relationships = new TreeMap<>();
    private final Map<String, StatAcc> statAccs = new TreeMap<>();

    ChunkWrites(QueryStatRepository stats) { this.stats = stats; }

    // ---- recording -----------------------------------------------------------------------------

    /** {@code touch}: first/last seen of a catalogue table. */
    void tableSeen(DbTable t, Instant ts) {
        TableSeen seen = tables.get(t.getId());
        if (seen == null) tables.put(t.getId(), new TableSeen(t, ts, ts));
        else if (ts.isAfter(seen.last)) seen.last = ts;
    }

    /** The last event of the chunk wins, as with sequential {@code setLastSeenAt}. */
    void routineSeen(Routine r, Instant ts) {
        routines.computeIfAbsent(r.getId(), k -> new RoutineSeen(r)).last = ts;
    }

    void relationship(String applicationId, Enums.ObjectType type, String objectId, Enums.RelationshipKind kind, Enums.RelationshipSource source,
                      String viaRoutineId, Instant ts) {
        String key = applicationId + '|' + type + '|' + objectId + '|' + kind + '|' + source + '|' + (viaRoutineId == null ? "" : viaRoutineId);
        RelationshipHits hits = relationships.get(key);
        if (hits == null) relationships.put(key, new RelationshipHits(applicationId, type, objectId, kind, source, viaRoutineId, ts));
        else hits.add(ts);
    }

    /** Folds one event into the hourly statistics row of (sql hash, application, database, hour). */
    void query(QueryEvent e, Instant ts, String hash, String appId, String dbId, List<Map<String, String>> tableRefs) {
        Instant bucket = ts.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        String key = hash + '|' + appId + '|' + dbId + '|' + bucket.toEpochMilli();
        StatAcc acc = statAccs.get(key);
        if (acc == null) {
            QueryStat s = stats.findBucket(hash, appId, dbId, bucket).orElseGet(() -> {
                QueryStat n = new QueryStat();
                n.setId(Ids.newId());
                n.setSqlHash(hash);
                n.setSqlNormalized(e.sqlNormalized());
                n.setOperation(e.operation() == null ? SqlOperation.OTHER.name() : e.operation().name());
                n.setApplicationId(appId);
                n.setDatabaseId(dbId);
                n.setDatasourceName(e.datasource());
                n.setBucketStart(bucket);
                return n;
            });
            acc = new StatAcc(s);
            statAccs.put(key, acc);
        }
        acc.add(e, ts, tableRefs);
    }

    // ---- applying ------------------------------------------------------------------------------

    void apply(CatalogueService catalogue) {
        for (TableSeen seen : tables.values()) {
            DbTable t = seen.table;
            if (t.getFirstSeenAt() == null) t.setFirstSeenAt(seen.first);
            if (t.getLastSeenAt() == null || seen.last.isAfter(t.getLastSeenAt())) t.setLastSeenAt(seen.last);
        }
        for (RoutineSeen seen : routines.values()) seen.routine.setLastSeenAt(seen.last);
        for (RelationshipHits h : relationships.values()) {
            catalogue.recordRelationship(h.applicationId, h.type, h.objectId, h.kind, h.source, h.viaRoutineId, h.count, h.first, h.last);
        }
        for (StatAcc acc : statAccs.values()) acc.applyAndSave(stats);
    }

    // ---- folded rows ---------------------------------------------------------------------------

    private static final class TableSeen {
        final DbTable table;
        final Instant first;
        Instant last;
        TableSeen(DbTable table, Instant first, Instant last) { this.table = table; this.first = first; this.last = last; }
    }

    private static final class RoutineSeen {
        final Routine routine;
        Instant last;
        RoutineSeen(Routine routine) { this.routine = routine; }
    }

    private static final class RelationshipHits {
        final String applicationId, objectId, viaRoutineId;
        final Enums.ObjectType type;
        final Enums.RelationshipKind kind;
        final Enums.RelationshipSource source;
        final Instant first;
        Instant last;
        long count = 1;
        RelationshipHits(String applicationId, Enums.ObjectType type, String objectId, Enums.RelationshipKind kind, Enums.RelationshipSource source,
                         String viaRoutineId, Instant ts) {
            this.applicationId = applicationId; this.type = type; this.objectId = objectId; this.kind = kind; this.source = source;
            this.viaRoutineId = viaRoutineId; this.first = ts; this.last = ts;
        }
        void add(Instant ts) {
            count++;
            if (ts.isAfter(last)) last = ts;
        }
    }

    /** The evolving values of one {@link QueryStat} row; the (managed) entity itself is only written by {@link #applyAndSave}. */
    private static final class StatAcc {
        final QueryStat entity;
        long execCount, totalDurationMs, maxDurationMs, rowCount, errorCount;
        Instant lastSeenAt;
        final List<Long> samples;
        final List<Map<String, String>> tables;
        boolean tablesChanged;

        StatAcc(QueryStat s) {
            entity = s;
            execCount = s.getExecCount(); totalDurationMs = s.getTotalDurationMs(); maxDurationMs = s.getMaxDurationMs();
            rowCount = s.getRowCount(); errorCount = s.getErrorCount(); lastSeenAt = s.getLastSeenAt();
            samples = Optional.ofNullable(Json.read(s.getDurationSamples(), new TypeReference<List<Long>>() {})).orElseGet(ArrayList::new);
            tables = Optional.ofNullable(Json.read(s.getTablesJson(), new TypeReference<List<Map<String, String>>>() {})).orElseGet(ArrayList::new);
        }

        void add(QueryEvent e, Instant ts, List<Map<String, String>> tableRefs) {
            execCount++;
            long dur = Math.max(0, e.durationMs());
            totalDurationMs += dur;
            maxDurationMs = Math.max(maxDurationMs, dur);
            if (e.rows() > 0) rowCount += e.rows();
            if (!e.success()) errorCount++;
            if (lastSeenAt == null || ts.isAfter(lastSeenAt)) lastSeenAt = ts;
            if (samples.size() < RESERVOIR) samples.add(dur);
            else samples.set((int) (execCount % RESERVOIR), dur);
            for (Map<String, String> ref : tableRefs) {
                if (tables.stream().noneMatch(m -> m.get("tableId").equals(ref.get("tableId")) && m.get("access").equals(ref.get("access")))) {
                    tables.add(ref);
                    tablesChanged = true;
                }
            }
        }

        void applyAndSave(QueryStatRepository repo) {
            entity.setExecCount(execCount);
            entity.setTotalDurationMs(totalDurationMs);
            entity.setMaxDurationMs(maxDurationMs);
            entity.setRowCount(rowCount);
            entity.setErrorCount(errorCount);
            entity.setLastSeenAt(lastSeenAt);
            entity.setDurationSamples(Json.writeCanonical(samples));
            if (tablesChanged) entity.setTablesJson(Json.writeCanonical(tables));
            repo.save(entity);
        }
    }
}
