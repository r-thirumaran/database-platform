package org.dbplatform.controlplane.service.graph;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
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

/**
 * Builds the ownership / producer-consumer graph (docs/control-plane-api.md §8). Node ids are
 * {@code <type>:<refId>} with a lower-case type. The whole graph is materialised in memory (POC sizes)
 * and traversed breadth-first from the optional root.
 */
@Service
@Transactional(readOnly = true)
public class GraphService {
    public record Node(String id, String type, String label, String refId, Map<String, Object> attrs) {}
    public record Edge(String id, String from, String to, String kind, Map<String, Object> attrs) {}
    public record Graph(List<Node> nodes, List<Edge> edges, boolean truncated) {}

    public static final Set<String> TYPES = Set.of("team", "application", "datasource", "database", "table", "routine");
    public static final Set<String> DEFAULT_INCLUDE = Set.of("teams", "applications", "datasources", "databases", "tables", "routines");

    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DatabaseRepository databases;
    private final DatasourceRepository datasources;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final DependencyRepository dependencies;
    private final RelationshipRepository relationships;
    private final AccessGrantRepository grants;

    public GraphService(TeamRepository teams, ApplicationRepository applications, DatabaseRepository databases, DatasourceRepository datasources,
                        DbTableRepository tables, RoutineRepository routines, DependencyRepository dependencies, RelationshipRepository relationships,
                        AccessGrantRepository grants) {
        this.teams = teams; this.applications = applications; this.databases = databases; this.datasources = datasources; this.tables = tables;
        this.routines = routines; this.dependencies = dependencies; this.relationships = relationships; this.grants = grants;
    }

    public Graph graph(String root, int depth, Set<String> include, Set<String> edgeKinds, int limit) {
        return graph(root, depth, include, edgeKinds, limit, false);
    }

    /** @param includeIndirect also emit application → table edges derived through a routine ({@code viaRoutineId} set) */
    public Graph graph(String root, int depth, Set<String> include, Set<String> edgeKinds, int limit, boolean includeIndirect) {
        Set<String> includeTypes = new HashSet<>();
        for (String inc : include == null || include.isEmpty() ? DEFAULT_INCLUDE : include) {
            String t = inc.trim().toLowerCase(Locale.ROOT);
            if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
            if (t.equals("databasee")) t = "database";
            includeTypes.add(t);
        }
        Set<String> kinds = edgeKinds == null || edgeKinds.isEmpty() ? null : edgeKinds.stream().map(k -> k.trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());

        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        build(nodes, edges, includeIndirect);

        // filter by node type and edge kind
        nodes.values().removeIf(n -> !includeTypes.contains(n.type().toLowerCase(Locale.ROOT)));
        edges.removeIf(e -> !nodes.containsKey(e.from()) || !nodes.containsKey(e.to()) || (kinds != null && !kinds.contains(e.kind())));

        if (root != null && !root.isBlank()) {
            String rootId = root.trim();
            int colon = rootId.indexOf(':');
            if (colon < 0 || !TYPES.contains(rootId.substring(0, colon).toLowerCase(Locale.ROOT))) {
                throw new ApiException.BadRequest("root must be <type>:<id> with type in " + TYPES);
            }
            rootId = rootId.substring(0, colon).toLowerCase(Locale.ROOT) + rootId.substring(colon);
            if (!nodes.containsKey(rootId)) throw new ApiException.NotFound("Graph node", rootId);
            Map<String, List<Edge>> adjacency = new HashMap<>();
            for (Edge e : edges) {
                adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                adjacency.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
            }
            Set<String> keep = new HashSet<>();
            Deque<String[]> queue = new ArrayDeque<>();
            queue.add(new String[]{rootId, "0"});
            keep.add(rootId);
            while (!queue.isEmpty()) {
                String[] cur = queue.poll();
                int d = Integer.parseInt(cur[1]);
                if (d >= depth) continue;
                for (Edge e : adjacency.getOrDefault(cur[0], List.of())) {
                    String other = e.from().equals(cur[0]) ? e.to() : e.from();
                    if (keep.add(other)) queue.add(new String[]{other, String.valueOf(d + 1)});
                }
            }
            nodes.keySet().retainAll(keep);
            edges.removeIf(e -> !keep.contains(e.from()) || !keep.contains(e.to()));
        }
        boolean truncated = false;
        List<Node> nodeList = new ArrayList<>(nodes.values());
        if (nodeList.size() > limit) {
            nodeList = new ArrayList<>(nodeList.subList(0, limit));
            Set<String> ids = new HashSet<>();
            nodeList.forEach(n -> ids.add(n.id()));
            edges.removeIf(e -> !ids.contains(e.from()) || !ids.contains(e.to()));
            truncated = true;
        }
        return new Graph(nodeList, edges, truncated);
    }

    public static String nodeId(String type, String refId) { return type.toLowerCase(Locale.ROOT) + ":" + refId; }

    private void build(Map<String, Node> nodes, List<Edge> edges, boolean includeIndirect) {
        Map<String, Team> teamMap = new HashMap<>();
        for (Team t : teams.findAll()) {
            teamMap.put(t.getId(), t);
            nodes.put(nodeId("team", t.getId()), new Node(nodeId("team", t.getId()), "TEAM", t.getName(), t.getId(), attrs("displayName", t.getDisplayName())));
        }
        Map<String, Application> appMap = new HashMap<>();
        for (Application a : applications.findAll()) {
            appMap.put(a.getId(), a);
            Team t = a.getTeamId() == null ? null : teamMap.get(a.getTeamId());
            nodes.put(nodeId("application", a.getId()), new Node(nodeId("application", a.getId()), "APPLICATION", a.getName(), a.getId(),
                    attrs("kind", a.getKind().name(), "team", t == null ? null : t.getName(), "teamId", a.getTeamId())));
            if (t != null) edges.add(edge(nodeId("application", a.getId()), nodeId("team", t.getId()), "BELONGS_TO", Map.of()));
        }
        Map<String, DatabaseInstance> dbMap = new HashMap<>();
        for (DatabaseInstance d : databases.findAll()) {
            dbMap.put(d.getId(), d);
            nodes.put(nodeId("database", d.getId()), new Node(nodeId("database", d.getId()), "DATABASE", d.getName(), d.getId(),
                    attrs("engine", d.getEngine().name(), "host", d.getHost(), "port", d.getPort())));
        }
        for (Datasource ds : datasources.findAll()) {
            Team t = ds.getOwnerTeamId() == null ? null : teamMap.get(ds.getOwnerTeamId());
            nodes.put(nodeId("datasource", ds.getId()), new Node(nodeId("datasource", ds.getId()), "DATASOURCE", ds.getName(), ds.getId(),
                    attrs("state", ds.getState().name(), "ownerTeam", t == null ? null : t.getName())));
            if (t != null) edges.add(edge(nodeId("team", t.getId()), nodeId("datasource", ds.getId()), "OWNS", Map.of()));
            if (ds.getCurrentDatabaseId() != null && dbMap.containsKey(ds.getCurrentDatabaseId())) {
                edges.add(edge(nodeId("datasource", ds.getId()), nodeId("database", ds.getCurrentDatabaseId()), "ROUTES_TO", Map.of("current", true)));
            }
            if (ds.getTargetDatabaseId() != null && dbMap.containsKey(ds.getTargetDatabaseId())) {
                edges.add(edge(nodeId("datasource", ds.getId()), nodeId("database", ds.getTargetDatabaseId()), "MIGRATES_TO", Map.of()));
            }
        }
        for (AccessGrant g : grants.findAll()) {
            if (appMap.containsKey(g.getApplicationId()) && nodes.containsKey(nodeId("datasource", g.getDatasourceId()))) {
                edges.add(edge(nodeId("application", g.getApplicationId()), nodeId("datasource", g.getDatasourceId()), "GRANTED",
                        attrs("enabled", g.isEnabled(), "readOnly", g.isReadOnly())));
            }
        }
        // query counts per table
        Map<String, Long> countPerObject = new HashMap<>();
        Map<String, Relationship> aggRel = new LinkedHashMap<>();
        for (Relationship r : relationships.findAll()) {
            countPerObject.merge(r.getObjectId(), r.getQueryCount(), Long::sum);
            if (r.getViaRoutineId() != null && !includeIndirect) continue;
            String key = r.getApplicationId() + "|" + r.getObjectType() + "|" + r.getObjectId() + "|" + r.getKind() + "|" + r.getViaRoutineId();
            Relationship prev = aggRel.get(key);
            if (prev == null) {
                aggRel.put(key, copy(r));
            } else {
                prev.setQueryCount(prev.getQueryCount() + r.getQueryCount());
                if (r.getLastSeenAt() != null && (prev.getLastSeenAt() == null || r.getLastSeenAt().isAfter(prev.getLastSeenAt()))) prev.setLastSeenAt(r.getLastSeenAt());
                if (r.isConfirmed() || r.getSource() == Enums.RelationshipSource.DECLARED) prev.setConfirmed(true);
                if (r.getSource() == Enums.RelationshipSource.DECLARED) prev.setSource(r.getSource());
            }
        }
        Map<String, DbTable> tableMap = new HashMap<>();
        for (DbTable t : tables.findAll()) {
            tableMap.put(t.getId(), t);
            DatabaseInstance db = dbMap.get(t.getDatabaseId());
            Team owner = t.getOwnerTeamId() == null ? null : teamMap.get(t.getOwnerTeamId());
            nodes.put(nodeId("table", t.getId()), new Node(nodeId("table", t.getId()), "TABLE", t.label(), t.getId(),
                    attrs("engine", db == null ? null : db.getEngine().name(), "database", db == null ? null : db.getName(), "schema", t.getSchema(),
                            "kind", t.getKind().name(), "ownerTeam", owner == null ? null : owner.getName(), "ownerSource", t.getOwnerSource().name(),
                            "queryCount", countPerObject.getOrDefault(t.getId(), 0L), "classification", t.getClassification() == null ? null : t.getClassification().name(),
                            "discovered", t.isDiscovered(), "migrationState", t.getMigration().getState().name())));
            if (owner != null) edges.add(edge(nodeId("team", owner.getId()), nodeId("table", t.getId()), "OWNS", attrs("confirmed", t.isOwnerConfirmed(), "source", t.getOwnerSource().name())));
            if (db != null) edges.add(edge(nodeId("database", db.getId()), nodeId("table", t.getId()), "HOSTS", Map.of()));
            if (t.getProducerApplicationId() != null && appMap.containsKey(t.getProducerApplicationId())) {
                edges.add(edge(nodeId("application", t.getProducerApplicationId()), nodeId("table", t.getId()), "PRODUCES",
                        attrs("source", t.getProducerSource() == null ? null : t.getProducerSource().name())));
            }
        }
        for (DbTable t : tableMap.values()) {
            if (t.getMigration().getTargetDatabaseId() != null && t.getMigration().getTargetName() != null) {
                tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(t.getMigration().getTargetDatabaseId(),
                        t.getMigration().getTargetSchema() == null ? t.getSchema() : t.getMigration().getTargetSchema(), t.getMigration().getTargetName())
                        .ifPresent(target -> edges.add(edge(nodeId("table", t.getId()), nodeId("table", target.getId()), "MIGRATES_TO", attrs("state", t.getMigration().getState().name()))));
            }
        }
        for (Routine r : routines.findAll()) {
            DatabaseInstance db = dbMap.get(r.getDatabaseId());
            Team owner = r.getOwnerTeamId() == null ? null : teamMap.get(r.getOwnerTeamId());
            nodes.put(nodeId("routine", r.getId()), new Node(nodeId("routine", r.getId()), "ROUTINE", r.label(), r.getId(),
                    attrs("kind", r.getKind().name(), "engine", db == null ? null : db.getEngine().name(), "database", db == null ? null : db.getName(),
                            "status", r.getStatus() == null ? null : r.getStatus().name(), "queryCount", countPerObject.getOrDefault(r.getId(), 0L), "ownerTeam", owner == null ? null : owner.getName())));
            if (owner != null) edges.add(edge(nodeId("team", owner.getId()), nodeId("routine", r.getId()), "OWNS", Map.of()));
            if (db != null) edges.add(edge(nodeId("database", db.getId()), nodeId("routine", r.getId()), "HOSTS", Map.of()));
        }
        for (Dependency d : dependencies.findAll()) {
            String from = nodeId(d.getFromType().name(), d.getFromId());
            String to = nodeId(d.getToType().name(), d.getToId());
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) continue;
            edges.add(new Edge("dep:" + d.getId(), from, to, d.getKind().name(), attrs("source", d.getSource().name(), "confidence", d.getConfidence(), "lastSeenAt", d.getLastSeenAt())));
        }
        for (Relationship r : aggRel.values()) {
            String from = nodeId("application", r.getApplicationId());
            String to = nodeId(r.getObjectType().name(), r.getObjectId());
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) continue;
            edges.add(new Edge("rel:" + r.getId(), from, to, r.getKind().name(), attrs("queryCount", r.getQueryCount(), "lastSeenAt", r.getLastSeenAt(),
                    "source", r.getSource().name(), "confirmed", r.isConfirmed(), "viaRoutineId", r.getViaRoutineId(), "confidence", r.getConfidence())));
        }
    }

    private static Relationship copy(Relationship r) {
        Relationship c = new Relationship();
        c.setId(r.getId()); c.setApplicationId(r.getApplicationId()); c.setObjectType(r.getObjectType()); c.setObjectId(r.getObjectId());
        c.setKind(r.getKind()); c.setSource(r.getSource()); c.setQueryCount(r.getQueryCount()); c.setLastSeenAt(r.getLastSeenAt());
        c.setFirstSeenAt(r.getFirstSeenAt()); c.setConfirmed(r.isConfirmed() || r.getSource() == Enums.RelationshipSource.DECLARED);
        c.setViaRoutineId(r.getViaRoutineId()); c.setConfidence(r.getConfidence());
        return c;
    }

    private static Edge edge(String from, String to, String kind, Map<String, Object> attrs) {
        return new Edge(kind.toLowerCase(Locale.ROOT) + ":" + from + ">" + to, from, to, kind, attrs);
    }

    static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1] instanceof Instant in ? in.toString() : kv[i + 1]);
        return m;
    }
}
