package org.dbplatform.controlplane.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.dbplatform.controlplane.api.dto.Refs.TableRef;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.PoolStatsSnapshot;
import org.dbplatform.controlplane.domain.QueryStat;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.QueryEventRawRepository;
import org.dbplatform.controlplane.repo.QueryStatRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.dbplatform.controlplane.repo.ViolationRepository;
import org.dbplatform.controlplane.service.telemetry.ComponentService;
import org.dbplatform.controlplane.service.telemetry.LiveConnection;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.dbplatform.controlplane.service.telemetry.TelemetryIngestService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observability endpoints (docs/control-plane-api.md §11). */
@Service
@Transactional(readOnly = true)
public class StatsService {
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DatabaseRepository databases;
    private final DatasourceRepository datasources;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final RelationshipRepository relationships;
    private final QueryStatRepository queryStats;
    private final QueryEventRawRepository rawQueries;
    private final ViolationRepository violations;
    private final ComponentService components;
    private final TelemetryIngestService telemetry;
    private final LiveConnectionRegistry live;
    private final DbpProperties props;

    public StatsService(TeamRepository teams, ApplicationRepository applications, DatabaseRepository databases, DatasourceRepository datasources,
                        DbTableRepository tables, RoutineRepository routines, RelationshipRepository relationships, QueryStatRepository queryStats,
                        QueryEventRawRepository rawQueries, ViolationRepository violations, ComponentService components,
                        TelemetryIngestService telemetry, LiveConnectionRegistry live, DbpProperties props) {
        this.teams = teams; this.applications = applications; this.databases = databases; this.datasources = datasources; this.tables = tables;
        this.routines = routines; this.relationships = relationships; this.queryStats = queryStats; this.rawQueries = rawQueries;
        this.violations = violations; this.components = components; this.telemetry = telemetry; this.live = live; this.props = props;
    }

    // ---- DTOs ------------------------------------------------------------------------------------

    public record QueryStats(long count24h, long count7d, Instant lastSeenAt) {}
    public record ConnectionStats(int proxy, int gatewayLogical, int gatewayPhysical) {}
    public record ComponentOnline(Enums.ComponentType componentType, String componentId, Instant lastHeartbeat, boolean healthy) {}
    public record Overview(long databases, long datasources, long applications, long teams, long tables, long routines,
                           ConnectionStats connections, long queriesLastHour, long unownedTables, long crossTeamAccesses, long violations,
                           List<ComponentOnline> componentsOnline) {}
    public record GroupedConnections(String key, int proxy, int gatewayLogical, int gatewayPhysical) {}
    public record QueryStatView(String sqlHash, String sqlNormalized, String operation, String applicationId, String applicationName,
                                String databaseId, List<TableRef> tables, long count, double avgDurationMs, long p95DurationMs,
                                long maxDurationMs, long rows, long errors, Instant lastSeenAt) {}
    public record HotTable(TableRef table, long reads, long writes, int applications, int teams) {}

    // ---- overview --------------------------------------------------------------------------------

    public Overview overview() {
        Map<String, Application> apps = index(applications.findAll(), Application::getId);
        Map<String, DbTable> tbls = index(tables.findAll(), DbTable::getId);
        long crossTeam = 0;
        for (Relationship r : relationships.findAll()) {
            if (r.getObjectType() != Enums.ObjectType.TABLE) continue;
            DbTable t = tbls.get(r.getObjectId());
            Application a = apps.get(r.getApplicationId());
            if (t != null && a != null && t.getOwnerTeamId() != null && a.getTeamId() != null && !t.getOwnerTeamId().equals(a.getTeamId())) crossTeam++;
        }
        List<ComponentOnline> online = components.list().stream()
                .map(c -> new ComponentOnline(c.componentType(), c.componentId(), c.lastHeartbeat(), c.healthy())).toList();
        return new Overview(databases.count(), datasources.count(), applications.count(), teams.count(), tables.count(), routines.count(),
                connectionTotals(), rawQueries.countByReceivedAtGreaterThanEqual(Instant.now().minus(Duration.ofHours(1))),
                tables.countByOwnerTeamIdIsNull(), crossTeam, violations.countByStatus(Enums.ViolationStatus.OPEN), online);
    }

    /** Number of healthy gateway instances known from heartbeats. */
    public long gatewayInstances() {
        return components.list().stream().filter(c -> c.componentType() == Enums.ComponentType.GATEWAY && c.healthy()).count();
    }

    public ConnectionStats connectionTotals() {
        int proxy = live.proxyRows().size();
        int logical = 0, physical = 0;
        for (PoolStatsSnapshot p : telemetry.latestPools()) {
            logical += nz(p.getLogicalSessions());
            physical += nz(p.getTotal());
        }
        return new ConnectionStats(proxy, logical, physical);
    }

    public ConnectionStats connectionsForApplication(String applicationId) {
        int proxy = (int) live.proxyRows().stream().filter(r -> applicationId.equals(r.applicationId())).count();
        return new ConnectionStats(proxy, 0, 0);
    }

    public ConnectionStats connectionsForDatasource(String datasourceId) {
        int proxy = (int) live.proxyRows().stream().filter(r -> datasourceId.equals(r.datasourceId())).count();
        int logical = 0, physical = 0;
        for (PoolStatsSnapshot p : telemetry.latestPools()) {
            if (datasourceId.equals(p.getDatasourceId())) { logical += nz(p.getLogicalSessions()); physical += nz(p.getTotal()); }
        }
        return new ConnectionStats(proxy, logical, physical);
    }

    // ---- grouped connections ---------------------------------------------------------------------

    public List<GroupedConnections> connectionsGroupedBy(String groupBy) {
        Map<String, Application> apps = index(applications.findAll(), Application::getId);
        Map<String, Team> tms = index(teams.findAll(), Team::getId);
        Map<String, Datasource> dss = index(datasources.findAll(), Datasource::getId);
        Map<String, DatabaseInstance> dbs = index(databases.findAll(), DatabaseInstance::getId);
        Map<String, int[]> acc = new LinkedHashMap<>();
        String gb = groupBy == null ? "application" : groupBy.toLowerCase();
        for (LiveConnection c : live.proxyRows()) {
            String key = switch (gb) {
                case "database" -> c.database();
                case "datasource" -> c.datasource();
                case "team" -> c.team();
                default -> c.application();
            };
            acc.computeIfAbsent(key == null ? "unknown" : key, k -> new int[3])[0]++;
        }
        for (PoolStatsSnapshot p : telemetry.latestPools()) {
            Datasource ds = p.getDatasourceId() == null ? null : dss.get(p.getDatasourceId());
            String key = switch (gb) {
                case "database" -> {
                    String dbId = p.getDatabaseId() != null ? p.getDatabaseId() : ds == null ? null : ds.getCurrentDatabaseId();
                    DatabaseInstance db = dbId == null ? null : dbs.get(dbId);
                    yield db == null ? dbId : db.getName();
                }
                case "datasource" -> p.getDatasourceName();
                case "team" -> ds == null || ds.getOwnerTeamId() == null ? null : tms.getOrDefault(ds.getOwnerTeamId(), new Team()).getName();
                default -> null; // pools are not attributable to applications
            };
            if (key == null && !"application".equals(gb)) key = "unknown";
            if (key == null) continue;
            int[] a = acc.computeIfAbsent(key, k -> new int[3]);
            a[1] += nz(p.getLogicalSessions());
            a[2] += nz(p.getTotal());
        }
        List<GroupedConnections> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new GroupedConnections(k, v[0], v[1], v[2])));
        out.sort(Comparator.comparing(GroupedConnections::key));
        return out;
    }

    // ---- queries ---------------------------------------------------------------------------------

    public static Duration window(String w) {
        if (w == null) return Duration.ofHours(24);
        return switch (w.toLowerCase()) {
            case "1h" -> Duration.ofHours(1);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            default -> Duration.ofHours(24);
        };
    }

    public List<QueryStatView> topQueries(String by, String window, String databaseId, String applicationId, int limit) {
        Instant since = Instant.now().minus(window(window)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        List<QueryStat> rows = queryStats.findSince(since, blankToNull(databaseId), blankToNull(applicationId));
        return rank(rows, by, limit);
    }

    public List<QueryStatView> topQueriesForTable(String tableId, String window, int limit) {
        Instant since = Instant.now().minus(window(window)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        return rank(queryStats.findSinceForTable(since, "%" + tableId + "%"), "count", limit);
    }

    private List<QueryStatView> rank(List<QueryStat> rows, String by, int limit) {
        Map<String, Application> apps = index(applications.findAll(), Application::getId);
        Map<String, Agg> agg = new LinkedHashMap<>();
        for (QueryStat s : rows) {
            String key = s.getSqlHash() + "|" + s.getApplicationId() + "|" + s.getDatabaseId();
            agg.computeIfAbsent(key, k -> new Agg(s)).add(s);
        }
        Comparator<Agg> cmp = switch (by == null ? "count" : by.toLowerCase()) {
            case "duration" -> Comparator.comparingLong((Agg a) -> a.totalDuration).reversed();
            case "rows" -> Comparator.comparingLong((Agg a) -> a.rows).reversed();
            default -> Comparator.comparingLong((Agg a) -> a.count).reversed();
        };
        List<QueryStatView> out = new ArrayList<>();
        agg.values().stream().sorted(cmp).limit(Math.max(1, limit)).forEach(a -> {
            Set<String> tableIds = new HashSet<>();
            a.tables.forEach(m -> tableIds.add(m.get("tableId")));
            List<TableRef> refs = tableIds.isEmpty() ? List.of() : tables.findByIdIn(tableIds).stream().map(TableRef::of).toList();
            Application app = a.first.getApplicationId() == null ? null : apps.get(a.first.getApplicationId());
            out.add(new QueryStatView(a.first.getSqlHash(), a.first.getSqlNormalized(), a.first.getOperation(), a.first.getApplicationId(),
                    app == null ? null : app.getName(), a.first.getDatabaseId(), refs, a.count,
                    a.count == 0 ? 0 : Math.round(a.totalDuration * 100.0 / a.count) / 100.0, a.p95(), a.maxDuration, a.rows, a.errors, a.lastSeen));
        });
        return out;
    }

    private static final class Agg {
        final QueryStat first;
        long count, totalDuration, maxDuration, rows, errors;
        Instant lastSeen;
        final List<Long> samples = new ArrayList<>();
        final List<Map<String, String>> tables = new ArrayList<>();
        Agg(QueryStat s) { first = s; }
        void add(QueryStat s) {
            count += s.getExecCount(); totalDuration += s.getTotalDurationMs(); maxDuration = Math.max(maxDuration, s.getMaxDurationMs());
            rows += s.getRowCount(); errors += s.getErrorCount();
            if (s.getLastSeenAt() != null && (lastSeen == null || s.getLastSeenAt().isAfter(lastSeen))) lastSeen = s.getLastSeenAt();
            List<Long> sm = Json.read(s.getDurationSamples(), new TypeReference<List<Long>>() {});
            if (sm != null) samples.addAll(sm);
            List<Map<String, String>> t = Json.read(s.getTablesJson(), new TypeReference<List<Map<String, String>>>() {});
            if (t != null) tables.addAll(t);
        }
        long p95() {
            if (samples.isEmpty()) return maxDuration;
            List<Long> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }

    public QueryStats queryStatsFor(java.util.function.Predicate<QueryStat> filter) {
        Instant since7d = Instant.now().minus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Instant since24h = Instant.now().minus(Duration.ofHours(24));
        long c24 = 0, c7 = 0;
        Instant last = null;
        for (QueryStat s : queryStats.findByBucketStartGreaterThanEqual(since7d)) {
            if (!filter.test(s)) continue;
            c7 += s.getExecCount();
            if (!s.getBucketStart().isBefore(since24h.truncatedTo(java.time.temporal.ChronoUnit.HOURS))) c24 += s.getExecCount();
            if (s.getLastSeenAt() != null && (last == null || s.getLastSeenAt().isAfter(last))) last = s.getLastSeenAt();
        }
        return new QueryStats(c24, c7, last);
    }

    public QueryStats queryStatsForTable(String tableId) {
        return queryStatsFor(s -> s.getTablesJson() != null && s.getTablesJson().contains(tableId));
    }

    public QueryStats queryStatsForApplication(String applicationId) {
        return queryStatsFor(s -> applicationId.equals(s.getApplicationId()));
    }

    // ---- tables ----------------------------------------------------------------------------------

    public List<HotTable> hotTables(String window, int limit) {
        Instant since = Instant.now().minus(window(window)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Map<String, Application> apps = index(applications.findAll(), Application::getId);
        Map<String, long[]> counts = new HashMap<>();
        Map<String, Set<String>> appsPer = new HashMap<>();
        for (QueryStat s : queryStats.findByBucketStartGreaterThanEqual(since)) {
            List<Map<String, String>> t = Json.read(s.getTablesJson(), new TypeReference<List<Map<String, String>>>() {});
            if (t == null) continue;
            for (Map<String, String> ref : t) {
                long[] c = counts.computeIfAbsent(ref.get("tableId"), k -> new long[2]);
                if ("WRITE".equals(ref.get("access"))) c[1] += s.getExecCount(); else c[0] += s.getExecCount();
                if (s.getApplicationId() != null) appsPer.computeIfAbsent(ref.get("tableId"), k -> new HashSet<>()).add(s.getApplicationId());
            }
        }
        if (counts.isEmpty()) {
            // no hourly stats in the window: fall back to relationship counters (e.g. seeded or collector data)
            for (Relationship r : relationships.findAll()) {
                if (r.getObjectType() != Enums.ObjectType.TABLE || r.getLastSeenAt() == null || r.getLastSeenAt().isBefore(since)) continue;
                long[] c = counts.computeIfAbsent(r.getObjectId(), k -> new long[2]);
                if (r.getKind() == Enums.RelationshipKind.WRITES) c[1] += r.getQueryCount(); else c[0] += r.getQueryCount();
                appsPer.computeIfAbsent(r.getObjectId(), k -> new HashSet<>()).add(r.getApplicationId());
            }
        }
        Map<String, DbTable> tbls = counts.isEmpty() ? Map.of() : index(tables.findByIdIn(counts.keySet()), DbTable::getId);
        List<HotTable> out = new ArrayList<>();
        counts.forEach((id, c) -> {
            DbTable t = tbls.get(id);
            if (t == null) return;
            Set<String> as = appsPer.getOrDefault(id, Set.of());
            Set<String> ts = new HashSet<>();
            for (String a : as) { Application ap = apps.get(a); if (ap != null && ap.getTeamId() != null) ts.add(ap.getTeamId()); }
            out.add(new HotTable(TableRef.of(t), c[0], c[1], as.size(), ts.size()));
        });
        out.sort(Comparator.comparingLong((HotTable h) -> h.reads() + h.writes()).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public List<TableRef> unusedTables(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        Set<String> used = new HashSet<>(relationships.tableIdsSeenSince(since));
        return tables.findAll().stream().filter(t -> !used.contains(t.getId()))
                .sorted(Comparator.comparing(DbTable::getSchema).thenComparing(DbTable::getName)).map(TableRef::of).toList();
    }

    public List<PoolStatsSnapshot> pools() { return telemetry.latestPools(); }

    public List<LiveConnection> liveConnections() { return live.all(); }

    public int staleDays() { return props.getRelationship().getStaleDays(); }

    // ---- helpers ---------------------------------------------------------------------------------

    static <T> Map<String, T> index(List<T> list, Function<T, String> key) {
        Map<String, T> m = new HashMap<>();
        for (T t : list) m.put(key.apply(t), t);
        return m;
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
