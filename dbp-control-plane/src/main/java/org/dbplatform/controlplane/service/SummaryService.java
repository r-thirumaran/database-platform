package org.dbplatform.controlplane.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dbplatform.controlplane.api.dto.Refs.DatasourceRef;
import org.dbplatform.controlplane.api.dto.Refs.RoutineRef;
import org.dbplatform.controlplane.api.dto.Refs.TableRef;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.DependencyKind;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.PoolStatsSnapshot;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.DependencyRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@code /summary} documents of teams, applications, datasources, tables and routines. */
@Service
@Transactional(readOnly = true)
public class SummaryService {
    private final TeamService teamService;
    private final ApplicationService applicationService;
    private final DatasourceService datasourceService;
    private final CatalogueService catalogue;
    private final StatsService stats;
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DatabaseRepository databases;
    private final DatasourceRepository datasources;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final DependencyRepository dependencies;
    private final RelationshipRepository relationships;
    private final AccessGrantRepository grants;

    public SummaryService(TeamService teamService, ApplicationService applicationService, DatasourceService datasourceService,
                          CatalogueService catalogue, StatsService stats, TeamRepository teams, ApplicationRepository applications,
                          DatabaseRepository databases, DatasourceRepository datasources, DbTableRepository tables, RoutineRepository routines,
                          DependencyRepository dependencies, RelationshipRepository relationships, AccessGrantRepository grants) {
        this.teamService = teamService; this.applicationService = applicationService; this.datasourceService = datasourceService;
        this.catalogue = catalogue; this.stats = stats; this.teams = teams; this.applications = applications; this.databases = databases;
        this.datasources = datasources; this.tables = tables; this.routines = routines; this.dependencies = dependencies;
        this.relationships = relationships; this.grants = grants;
    }

    public record Consumer(Application application, Team team, RelationshipKind kind, long queryCount, Instant lastSeenAt,
                           RoutineRef viaRoutine, Enums.RelationshipSource source, boolean confirmed, boolean stale) {}

    public record TeamSummary(Team team, List<Application> applications, List<TableRef> ownedTables, List<TableRef> consumedTables,
                              List<TableRef> producedTables, List<DatasourceRef> datasourcesOwned) {}

    public record ApplicationSummary(Application application, Team team, List<AccessGrant> grants, List<TableRef> reads, List<TableRef> writes,
                                     List<RoutineRef> calls, StatsService.ConnectionStats connections, StatsService.QueryStats queryStats) {}

    public record DatasourceSummary(Datasource datasource, Team ownerTeam, DatabaseInstance currentDatabase, DatabaseInstance targetDatabase,
                                    List<AccessGrant> grants, List<Application> consumers, List<PoolStatsSnapshot> pools, List<TableRef> tables) {}

    public record TableSummary(DbTable table, DatabaseInstance database, Team ownerTeam, Application producer, List<Consumer> consumers,
                               List<RoutineRef> routines, List<RoutineRef> triggers, List<TableRef> foreignKeysOut, List<TableRef> foreignKeysIn,
                               List<RoutineRef> views, StatsService.QueryStats queryStats, List<StatsService.QueryStatView> topQueries) {}

    public record ResolvedDependency(String id, ObjectType fromType, String fromId, String fromLabel, ObjectType toType, String toId, String toLabel,
                                     DependencyKind kind, Enums.DependencySource source, double confidence, Instant firstSeenAt, Instant lastSeenAt) {}

    public record RoutineSummary(Routine routine, List<ResolvedDependency> dependencies, List<Application> callers, List<TableRef> tables,
                                 DbTable triggerTable, Team ownerTeam, DatabaseInstance database) {}

    public TeamSummary team(String id) {
        Team team = teamService.get(id);
        List<Application> apps = applications.findByTeamId(id).stream().sorted(Comparator.comparing(Application::getName)).toList();
        Set<String> appIds = new LinkedHashSet<>();
        apps.forEach(a -> appIds.add(a.getId()));
        List<TableRef> owned = tables.findByOwnerTeamId(id).stream().sorted(byName()).map(TableRef::of).toList();
        Set<String> consumedIds = new LinkedHashSet<>();
        for (String appId : appIds) for (Relationship r : relationships.findByApplicationId(appId)) if (r.getObjectType() == ObjectType.TABLE) consumedIds.add(r.getObjectId());
        List<TableRef> consumed = catalogue.tablesByIds(consumedIds).stream().sorted(byName()).map(TableRef::of).toList();
        List<TableRef> produced = new ArrayList<>();
        for (String appId : appIds) tables.findByProducerApplicationId(appId).forEach(t -> produced.add(TableRef.of(t)));
        List<DatasourceRef> owningDs = datasources.findByOwnerTeamId(id).stream().sorted(Comparator.comparing(Datasource::getName)).map(DatasourceRef::of).toList();
        return new TeamSummary(team, apps, owned, consumed, produced, owningDs);
    }

    public ApplicationSummary application(String id) {
        Application app = applicationService.get(id);
        Team team = app.getTeamId() == null ? null : teams.findById(app.getTeamId()).orElse(null);
        Set<String> readIds = new LinkedHashSet<>(), writeIds = new LinkedHashSet<>(), callIds = new LinkedHashSet<>();
        for (Relationship r : relationships.findByApplicationId(id)) {
            switch (r.getKind()) {
                case READS -> readIds.add(r.getObjectId());
                case WRITES -> writeIds.add(r.getObjectId());
                case CALLS -> callIds.add(r.getObjectId());
            }
        }
        return new ApplicationSummary(app, team, grants.findByApplicationId(id),
                catalogue.tablesByIds(readIds).stream().sorted(byName()).map(TableRef::of).toList(),
                catalogue.tablesByIds(writeIds).stream().sorted(byName()).map(TableRef::of).toList(),
                catalogue.routinesByIds(callIds).stream().sorted(Comparator.comparing(Routine::getName)).map(RoutineRef::of).toList(),
                stats.connectionsForApplication(id), stats.queryStatsForApplication(id));
    }

    public DatasourceSummary datasource(String id) {
        Datasource ds = datasourceService.get(id);
        Team owner = ds.getOwnerTeamId() == null ? null : teams.findById(ds.getOwnerTeamId()).orElse(null);
        DatabaseInstance cur = ds.getCurrentDatabaseId() == null ? null : databases.findById(ds.getCurrentDatabaseId()).orElse(null);
        DatabaseInstance tgt = ds.getTargetDatabaseId() == null ? null : databases.findById(ds.getTargetDatabaseId()).orElse(null);
        List<AccessGrant> gs = grants.findByDatasourceId(id);
        List<Application> consumers = new ArrayList<>();
        for (AccessGrant g : gs) applications.findById(g.getApplicationId()).ifPresent(consumers::add);
        consumers.sort(Comparator.comparing(Application::getName));
        List<PoolStatsSnapshot> pools = stats.pools().stream().filter(p -> id.equals(p.getDatasourceId()) || ds.getName().equals(p.getDatasourceName())).toList();
        List<TableRef> tbls = cur == null ? List.of() : tables.findByDatabaseIdOrderBySchemaAscNameAsc(cur.getId()).stream().map(TableRef::of).toList();
        return new DatasourceSummary(ds, owner, cur, tgt, gs, consumers, pools, tbls);
    }

    public TableSummary table(String id) {
        DbTable t = catalogue.getTable(id);
        DatabaseInstance db = databases.findById(t.getDatabaseId()).orElse(null);
        Team owner = t.getOwnerTeamId() == null ? null : teams.findById(t.getOwnerTeamId()).orElse(null);
        Application producer = t.getProducerApplicationId() == null ? null : applications.findById(t.getProducerApplicationId()).orElse(null);
        List<Consumer> consumers = consumersOf(id);
        List<RoutineRef> routs = new ArrayList<>(), trigs = new ArrayList<>(), views = new ArrayList<>();
        List<TableRef> fkOut = new ArrayList<>(), fkIn = new ArrayList<>();
        for (Dependency d : dependencies.findByToId(id)) {
            if (d.getFromType() == ObjectType.ROUTINE) {
                routines.findById(d.getFromId()).ifPresent(r -> {
                    RoutineRef ref = RoutineRef.of(r);
                    if (r.getKind() == Enums.RoutineKind.VIEW) { if (!views.contains(ref)) views.add(ref); }
                    else if (r.getKind() == Enums.RoutineKind.TRIGGER) { if (!trigs.contains(ref)) trigs.add(ref); }
                    else if (!routs.contains(ref)) routs.add(ref);
                });
            } else if (d.getKind() == DependencyKind.FOREIGN_KEY) {
                tables.findById(d.getFromId()).ifPresent(x -> fkIn.add(TableRef.of(x)));
            }
        }
        for (Dependency d : dependencies.findByFromId(id)) {
            if (d.getKind() == DependencyKind.FOREIGN_KEY) tables.findById(d.getToId()).ifPresent(x -> fkOut.add(TableRef.of(x)));
            else if (d.getKind() == DependencyKind.TRIGGERS) routines.findById(d.getToId()).ifPresent(r -> { RoutineRef ref = RoutineRef.of(r); if (!trigs.contains(ref)) trigs.add(ref); });
        }
        for (Routine r : routines.findByTriggerTableId(id)) { RoutineRef ref = RoutineRef.of(r); if (!trigs.contains(ref)) trigs.add(ref); }
        return new TableSummary(t, db, owner, producer, consumers, routs, trigs, fkOut, fkIn, views,
                stats.queryStatsForTable(id), stats.topQueriesForTable(id, "7d", 10));
    }

    /** Consumers of an object aggregated per (application, kind, viaRoutine) across telemetry sources. */
    public List<Consumer> consumersOf(String objectId) {
        Map<String, Application> appCache = new HashMap<>();
        Map<String, Team> teamCache = new HashMap<>();
        Map<String, Consumer> agg = new LinkedHashMap<>();
        Instant staleBefore = Instant.now().minusSeconds(86400L * stats.staleDays());
        for (Relationship r : relationships.findByObjectId(objectId)) {
            Application app = appCache.computeIfAbsent(r.getApplicationId(), k -> applications.findById(k).orElse(null));
            if (app == null) continue;
            Team team = app.getTeamId() == null ? null : teamCache.computeIfAbsent(app.getTeamId(), k -> teams.findById(k).orElse(null));
            RoutineRef via = r.getViaRoutineId() == null ? null : routines.findById(r.getViaRoutineId()).map(RoutineRef::of).orElse(null);
            String key = app.getId() + "|" + r.getKind() + "|" + r.getViaRoutineId();
            Consumer prev = agg.get(key);
            long count = r.getQueryCount() + (prev == null ? 0 : prev.queryCount());
            Instant last = prev != null && prev.lastSeenAt() != null && (r.getLastSeenAt() == null || prev.lastSeenAt().isAfter(r.getLastSeenAt())) ? prev.lastSeenAt() : r.getLastSeenAt();
            boolean confirmed = r.isConfirmed() || r.getSource() == Enums.RelationshipSource.DECLARED || (prev != null && prev.confirmed());
            Enums.RelationshipSource src = prev == null ? r.getSource() : prev.source() == Enums.RelationshipSource.DECLARED ? prev.source() : r.getSource();
            agg.put(key, new Consumer(app, team, r.getKind(), count, last, via, src, confirmed, last == null || last.isBefore(staleBefore)));
        }
        return agg.values().stream().sorted(Comparator.comparingLong(Consumer::queryCount).reversed()).toList();
    }

    public RoutineSummary routine(String id) {
        Routine r = catalogue.getRoutine(id);
        List<ResolvedDependency> deps = new ArrayList<>();
        for (Dependency d : dependencies.findByFromId(id)) deps.add(resolve(d));
        for (Dependency d : dependencies.findByToId(id)) deps.add(resolve(d));
        Set<String> callerIds = new LinkedHashSet<>();
        relationships.findByObjectId(id).forEach(rel -> callerIds.add(rel.getApplicationId()));
        List<Application> callers = callerIds.stream().map(applications::findById).flatMap(java.util.Optional::stream).sorted(Comparator.comparing(Application::getName)).toList();
        List<TableRef> tbls = catalogue.tablesByIds(catalogue.expandRoutineToTables(id).keySet()).stream().sorted(byName()).map(TableRef::of).toList();
        DbTable trigTable = r.getTriggerTableId() == null ? null : tables.findById(r.getTriggerTableId()).orElse(null);
        Team owner = r.getOwnerTeamId() == null ? null : teams.findById(r.getOwnerTeamId()).orElse(null);
        return new RoutineSummary(r, deps, callers, tbls, trigTable, owner, databases.findById(r.getDatabaseId()).orElse(null));
    }

    private ResolvedDependency resolve(Dependency d) {
        return new ResolvedDependency(d.getId(), d.getFromType(), d.getFromId(), labelOf(d.getFromType(), d.getFromId()),
                d.getToType(), d.getToId(), labelOf(d.getToType(), d.getToId()), d.getKind(), d.getSource(), d.getConfidence(), d.getFirstSeenAt(), d.getLastSeenAt());
    }

    private String labelOf(ObjectType type, String id) {
        return type == ObjectType.TABLE ? tables.findById(id).map(DbTable::label).orElse(id) : routines.findById(id).map(Routine::label).orElse(id);
    }

    private static Comparator<DbTable> byName() { return Comparator.comparing(DbTable::getSchema).thenComparing(DbTable::getName); }
}
