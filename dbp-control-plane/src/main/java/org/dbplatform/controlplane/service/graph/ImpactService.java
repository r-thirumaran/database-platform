package org.dbplatform.controlplane.service.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dbplatform.controlplane.api.dto.Refs.RoutineRef;
import org.dbplatform.controlplane.api.dto.Refs.TableRef;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbColumn;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.DependencyKind;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.QueryStat;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.DependencyRepository;
import org.dbplatform.controlplane.repo.QueryStatRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.DatasourceService;
import org.dbplatform.controlplane.service.StatsService;
import org.dbplatform.controlplane.service.SummaryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Impact analysis for tables, columns and datasources (docs/control-plane-api.md §8, docs/metadata-model.md). */
@Service
@Transactional(readOnly = true)
public class ImpactService {
    public record Target(String type, String id, String label) {}
    public record DirectConsumer(Application application, Team team, Enums.RelationshipKind kind, long queryCount, Instant lastSeenAt, boolean confirmed) {}
    public record IndirectConsumer(Application application, Team team, RoutineRef viaRoutine, Enums.RelationshipKind kind, long queryCount, Instant lastSeenAt) {}
    public record Impact(Target target, Team owner, Application producer, List<DirectConsumer> directConsumers, List<IndirectConsumer> indirectConsumers,
                         List<RoutineRef> routines, List<RoutineRef> triggers, List<RoutineRef> dependentViews, List<TableRef> foreignKeyDependents,
                         List<Team> teamsAffected, StatsService.QueryStats queryStats, double riskScore, List<String> riskFactors,
                         DbColumn column, List<StatsService.QueryStatView> queriesReferencingColumn) {}
    public record DatasourceImpact(Target target, Datasource datasource, DatabaseInstance currentDatabase, DatabaseInstance targetDatabase,
                                   List<Application> consumers, List<Team> teamsAffected, List<TableImpactRow> tables, double riskScore, List<String> riskFactors) {}
    public record TableImpactRow(TableRef table, Team owner, int consumers, int teams, double riskScore, List<String> riskFactors) {}

    private final CatalogueService catalogue;
    private final SummaryService summaries;
    private final StatsService stats;
    private final DatasourceService datasourceService;
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final DependencyRepository dependencies;
    private final RelationshipRepository relationships;
    private final DatabaseRepository databases;
    private final QueryStatRepository queryStats;

    public ImpactService(CatalogueService catalogue, SummaryService summaries, StatsService stats, DatasourceService datasourceService,
                         TeamRepository teams, ApplicationRepository applications, DbTableRepository tables, RoutineRepository routines,
                         DependencyRepository dependencies, RelationshipRepository relationships, DatabaseRepository databases, QueryStatRepository queryStats) {
        this.catalogue = catalogue; this.summaries = summaries; this.stats = stats; this.datasourceService = datasourceService; this.teams = teams;
        this.applications = applications; this.tables = tables; this.routines = routines; this.dependencies = dependencies;
        this.relationships = relationships; this.databases = databases; this.queryStats = queryStats;
    }

    public Impact table(String tableId) {
        DbTable t = catalogue.getTable(tableId);
        return analyse(t, null);
    }

    public Impact column(String columnId) {
        DbColumn c = catalogue.getColumn(columnId);
        DbTable t = catalogue.getTable(c.getTableId());
        return analyse(t, c);
    }

    private Impact analyse(DbTable t, DbColumn column) {
        Team owner = t.getOwnerTeamId() == null ? null : teams.findById(t.getOwnerTeamId()).orElse(null);
        Application producer = t.getProducerApplicationId() == null ? null : applications.findById(t.getProducerApplicationId()).orElse(null);
        Map<String, Application> appCache = new HashMap<>();
        Map<String, Team> teamCache = new HashMap<>();

        // 1. direct relationships
        Map<String, DirectConsumer> direct = new LinkedHashMap<>();
        Map<String, IndirectConsumer> indirect = new LinkedHashMap<>();
        for (SummaryService.Consumer c : summaries.consumersOf(t.getId())) {
            if (c.viaRoutine() == null) {
                String k = c.application().getId() + "|" + c.kind();
                direct.merge(k, new DirectConsumer(c.application(), c.team(), c.kind(), c.queryCount(), c.lastSeenAt(), c.confirmed()),
                        (a, b) -> new DirectConsumer(a.application(), a.team(), a.kind(), a.queryCount() + b.queryCount(), later(a.lastSeenAt(), b.lastSeenAt()), a.confirmed() || b.confirmed()));
            } else {
                indirect.put(c.application().getId() + "|" + c.kind() + "|" + c.viaRoutine().id(), new IndirectConsumer(c.application(), c.team(), c.viaRoutine(), c.kind(), c.queryCount(), c.lastSeenAt()));
            }
        }
        // 2. routines / triggers referencing the table, then the applications calling them
        List<RoutineRef> routs = new ArrayList<>(), trigs = new ArrayList<>(), views = new ArrayList<>();
        Set<String> routineIds = new LinkedHashSet<>();
        for (Dependency d : dependencies.findByToId(t.getId())) {
            if (d.getFromType() != ObjectType.ROUTINE) continue;
            routines.findById(d.getFromId()).ifPresent(r -> {
                routineIds.add(r.getId());
                RoutineRef ref = RoutineRef.of(r);
                if (r.getKind() == Enums.RoutineKind.VIEW) { if (!views.contains(ref)) views.add(ref); }
                else if (r.getKind() == Enums.RoutineKind.TRIGGER) { if (!trigs.contains(ref)) trigs.add(ref); }
                else if (!routs.contains(ref)) routs.add(ref);
            });
        }
        for (Dependency d : dependencies.findByFromId(t.getId())) {
            if (d.getKind() == DependencyKind.TRIGGERS) routines.findById(d.getToId()).ifPresent(r -> { routineIds.add(r.getId()); RoutineRef ref = RoutineRef.of(r); if (!trigs.contains(ref)) trigs.add(ref); });
        }
        for (Routine r : routines.findByTriggerTableId(t.getId())) { routineIds.add(r.getId()); RoutineRef ref = RoutineRef.of(r); if (!trigs.contains(ref)) trigs.add(ref); }
        // callers of routines/views that touch the table, transitively upwards (package → procedure callers)
        Set<String> expanded = new LinkedHashSet<>(routineIds);
        List<String> frontier = new ArrayList<>(routineIds);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (Dependency d : dependencies.findByToIdIn(frontier)) {
                if (d.getFromType() == ObjectType.ROUTINE && expanded.add(d.getFromId())) next.add(d.getFromId());
            }
            frontier = next;
        }
        for (String rid : expanded) {
            Routine r = routines.findById(rid).orElse(null);
            if (r == null) continue;
            for (Relationship rel : relationships.findByObjectId(rid)) {
                Application app = appCache.computeIfAbsent(rel.getApplicationId(), k -> applications.findById(k).orElse(null));
                if (app == null) continue;
                Team team = app.getTeamId() == null ? null : teamCache.computeIfAbsent(app.getTeamId(), k -> teams.findById(k).orElse(null));
                Enums.RelationshipKind kind = r.getKind() == Enums.RoutineKind.VIEW ? Enums.RelationshipKind.READS
                        : catalogue.expandRoutineToTables(rid).getOrDefault(t.getId(), Enums.RelationshipKind.READS);
                String k = app.getId() + "|" + kind + "|" + rid;
                if (!indirect.containsKey(k)) indirect.put(k, new IndirectConsumer(app, team, RoutineRef.of(r), kind, rel.getQueryCount(), rel.getLastSeenAt()));
            }
        }
        // 4. foreign keys pointing at it
        List<TableRef> fkDependents = new ArrayList<>();
        for (Dependency d : dependencies.findByToId(t.getId())) {
            if (d.getKind() == DependencyKind.FOREIGN_KEY && d.getFromType() == ObjectType.TABLE) tables.findById(d.getFromId()).ifPresent(x -> fkDependents.add(TableRef.of(x)));
        }
        // teams affected
        Map<String, Team> affected = new LinkedHashMap<>();
        direct.values().forEach(c -> { if (c.team() != null) affected.put(c.team().getId(), c.team()); });
        indirect.values().forEach(c -> { if (c.team() != null) affected.put(c.team().getId(), c.team()); });
        for (TableRef fk : fkDependents) tables.findById(fk.id()).ifPresent(x -> { if (x.getOwnerTeamId() != null) teams.findById(x.getOwnerTeamId()).ifPresent(tm -> affected.put(tm.getId(), tm)); });
        if (owner != null) affected.remove(owner.getId());
        List<Team> teamsAffected = new ArrayList<>(affected.values());
        teamsAffected.sort(Comparator.comparing(Team::getName));

        StatsService.QueryStats qs = stats.queryStatsForTable(t.getId());
        List<String> factors = new ArrayList<>();
        double score = risk(t, direct.size() + indirect.size(), teamsAffected.size(), trigs.size(), routs.size(), views.size(), qs, factors);

        List<StatsService.QueryStatView> colQueries = null;
        if (column != null) {
            String needle = (t.getName() + "." + column.getName()).toUpperCase();
            String needle2 = column.getName().toUpperCase();
            List<QueryStat> rows = queryStats.findSinceForTable(Instant.now().minusSeconds(7L * 86400), "%" + t.getId() + "%").stream()
                    .filter(s -> s.getSqlNormalized() != null && (s.getSqlNormalized().toUpperCase().contains(needle) || s.getSqlNormalized().toUpperCase().matches("(?s).*\\b" + java.util.regex.Pattern.quote(needle2) + "\\b.*")))
                    .toList();
            colQueries = stats.topQueriesForTable(t.getId(), "7d", 50).stream()
                    .filter(v -> rows.stream().anyMatch(s -> s.getSqlHash().equals(v.sqlHash()))).toList();
            if (column.getClassification() == Enums.Classification.PII) { factors.add("column classified PII"); score = Math.min(1.0, score + 0.1); }
        }
        Target target = column == null ? new Target("TABLE", t.getId(), t.label()) : new Target("COLUMN", column.getId(), t.label() + "." + column.getName());
        return new Impact(target, owner, producer, new ArrayList<>(direct.values()), new ArrayList<>(indirect.values()), routs, trigs, views, fkDependents,
                teamsAffected, qs, round(score), factors, column, colQueries);
    }

    /** Risk score 0..1 from consuming teams, write-path complexity, volume, classification and migration state. */
    static double risk(DbTable t, int consumers, int teamsAffected, int triggers, int routines, int views, StatsService.QueryStats qs, List<String> factors) {
        double score = 0;
        if (teamsAffected > 0) {
            score += Math.min(teamsAffected, 5) / 5.0 * 0.35;
            factors.add(teamsAffected + " consuming team" + (teamsAffected > 1 ? "s" : ""));
        }
        if (consumers > 0) { score += Math.min(consumers, 10) / 10.0 * 0.1; factors.add(consumers + " consuming application" + (consumers > 1 ? "s" : "")); }
        if (triggers > 0) { score += 0.15; factors.add("written by trigger"); }
        if (routines > 0) { score += 0.1; factors.add("referenced by " + routines + " routine" + (routines > 1 ? "s" : "")); }
        if (views > 0) { score += 0.05; factors.add(views + " dependent view" + (views > 1 ? "s" : "")); }
        if (qs != null && qs.count7d() > 0) {
            double vol = Math.min(Math.log10(qs.count7d() + 1) / 6.0, 1.0) * 0.15;
            score += vol;
            factors.add(qs.count7d() + " queries in 7d");
        }
        if (t.getClassification() == Enums.Classification.PII) { score += 0.15; factors.add("PII"); }
        else if (t.getClassification() == Enums.Classification.CONFIDENTIAL) { score += 0.1; factors.add("CONFIDENTIAL"); }
        if (t.getMigration().getState() == Enums.MigrationState.IN_PROGRESS) { score += 0.1; factors.add("migration in progress"); }
        if (t.getOwnerTeamId() == null) { score += 0.05; factors.add("no owner"); }
        return Math.min(1.0, score);
    }

    public DatasourceImpact datasource(String datasourceId) {
        Datasource ds = datasourceService.get(datasourceId);
        DatabaseInstance cur = ds.getCurrentDatabaseId() == null ? null : databases.findById(ds.getCurrentDatabaseId()).orElse(null);
        DatabaseInstance tgt = ds.getTargetDatabaseId() == null ? null : databases.findById(ds.getTargetDatabaseId()).orElse(null);
        Map<String, Application> consumers = new LinkedHashMap<>();
        Map<String, Team> teamsAffected = new LinkedHashMap<>();
        List<TableImpactRow> rows = new ArrayList<>();
        double maxRisk = 0;
        if (cur != null) {
            for (DbTable t : tables.findByDatabaseIdOrderBySchemaAscNameAsc(cur.getId())) {
                Impact i = analyse(t, null);
                Set<String> apps = new LinkedHashSet<>();
                i.directConsumers().forEach(c -> { apps.add(c.application().getId()); consumers.put(c.application().getId(), c.application()); if (c.team() != null) teamsAffected.put(c.team().getId(), c.team()); });
                i.indirectConsumers().forEach(c -> { apps.add(c.application().getId()); consumers.put(c.application().getId(), c.application()); if (c.team() != null) teamsAffected.put(c.team().getId(), c.team()); });
                Set<String> tms = new LinkedHashSet<>();
                i.teamsAffected().forEach(x -> tms.add(x.getId()));
                rows.add(new TableImpactRow(TableRef.of(t), i.owner(), apps.size(), tms.size(), i.riskScore(), i.riskFactors()));
                maxRisk = Math.max(maxRisk, i.riskScore());
            }
        }
        rows.sort(Comparator.comparingDouble(TableImpactRow::riskScore).reversed());
        List<String> factors = new ArrayList<>();
        double score = 0;
        if (!teamsAffected.isEmpty()) { score += Math.min(teamsAffected.size(), 5) / 5.0 * 0.4; factors.add(teamsAffected.size() + " consuming team" + (teamsAffected.size() > 1 ? "s" : "")); }
        if (!consumers.isEmpty()) { score += Math.min(consumers.size(), 10) / 10.0 * 0.2; factors.add(consumers.size() + " consuming application" + (consumers.size() > 1 ? "s" : "")); }
        score += maxRisk * 0.4;
        if (maxRisk > 0) factors.add("highest table risk " + maxRisk);
        if (ds.getState() == Enums.DatasourceState.MIGRATING) factors.add("datasource is MIGRATING");
        List<Team> tl = new ArrayList<>(teamsAffected.values());
        tl.sort(Comparator.comparing(Team::getName));
        List<Application> al = new ArrayList<>(consumers.values());
        al.sort(Comparator.comparing(Application::getName));
        return new DatasourceImpact(new Target("DATASOURCE", ds.getId(), ds.getName()), ds, cur, tgt, al, tl, rows, round(Math.min(1.0, score)), factors);
    }

    private static Instant later(Instant a, Instant b) { return a == null ? b : b == null ? a : a.isAfter(b) ? a : b; }
    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
}
