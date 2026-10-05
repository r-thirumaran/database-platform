package org.dbplatform.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.dbplatform.controlplane.api.dto.CredentialRequest;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.Credential;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.domain.SchemaOwnership;
import org.dbplatform.controlplane.domain.TableMigration;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.CredentialRepository;
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
 * {@code GET /export} / {@code POST /import}: the whole configuration as one JSON document, cross-referenced
 * <b>by name</b> ({@code team}, {@code credential}, {@code currentDatabase}, {@code targetDatabase}, {@code ownerTeam},
 * {@code routingRules[].application/database}, {@code accessGrants[].application/datasource}, {@code collector.credential})
 * with {@code ownership}, {@code producers}, {@code relationships} and {@code dependencies} sections — the format of
 * {@code deploy/bootstrap/platform-config.json}. Import upserts by name, tolerates unknown fields and also accepts the
 * {@code *Name} / {@code *Id} spellings. Secrets are never exported.
 */
@Service
public class ImportExportService {
    public static final int FORMAT_VERSION = 1;

    private final TeamService teamService;
    private final ApplicationService applicationService;
    private final CredentialService credentialService;
    private final DatabaseService databaseService;
    private final DatasourceService datasourceService;
    private final AccessGrantService grantService;
    private final CatalogueService catalogue;
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final CredentialRepository credentials;
    private final DatabaseRepository databases;
    private final DatasourceRepository datasources;
    private final AccessGrantRepository grants;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final RelationshipRepository relationships;
    private final DependencyRepository dependencies;

    public ImportExportService(TeamService teamService, ApplicationService applicationService, CredentialService credentialService,
                               DatabaseService databaseService, DatasourceService datasourceService, AccessGrantService grantService, CatalogueService catalogue,
                               TeamRepository teams, ApplicationRepository applications, CredentialRepository credentials, DatabaseRepository databases,
                               DatasourceRepository datasources, AccessGrantRepository grants, DbTableRepository tables, RoutineRepository routines,
                               RelationshipRepository relationships, DependencyRepository dependencies) {
        this.teamService = teamService; this.applicationService = applicationService; this.credentialService = credentialService;
        this.databaseService = databaseService; this.datasourceService = datasourceService; this.grantService = grantService; this.catalogue = catalogue;
        this.teams = teams; this.applications = applications; this.credentials = credentials; this.databases = databases; this.datasources = datasources;
        this.grants = grants; this.tables = tables; this.routines = routines; this.relationships = relationships; this.dependencies = dependencies;
    }

    // ---- export ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ObjectNode export() {
        Map<String, String> teamNames = new HashMap<>(), appNames = new HashMap<>(), credNames = new HashMap<>(), dbNames = new HashMap<>(), dsNames = new HashMap<>();
        teams.findAll().forEach(t -> teamNames.put(t.getId(), t.getName()));
        applications.findAll().forEach(a -> appNames.put(a.getId(), a.getName()));
        credentials.findAll().forEach(c -> credNames.put(c.getId(), c.getName()));
        databases.findAll().forEach(d -> dbNames.put(d.getId(), d.getName()));
        datasources.findAll().forEach(d -> dsNames.put(d.getId(), d.getName()));
        Map<String, DbTable> tableMap = new HashMap<>();
        tables.findAll().forEach(t -> tableMap.put(t.getId(), t));
        Map<String, Routine> routineMap = new HashMap<>();
        routines.findAll().forEach(r -> routineMap.put(r.getId(), r));

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("exportedAt", Instant.now().toString());
        ArrayNode tn = root.putArray("teams");
        teamService.list().forEach(t -> tn.add(Json.MAPPER.valueToTree(t)));
        ArrayNode an = root.putArray("applications");
        for (Application a : applicationService.list()) {
            ObjectNode n = Json.MAPPER.valueToTree(a);
            n.put("team", teamNames.get(a.getTeamId()));
            an.add(n);
        }
        ArrayNode cn = root.putArray("credentials");
        credentialService.list().forEach(c -> cn.add(Json.MAPPER.valueToTree(c)));
        ArrayNode dbn = root.putArray("databases");
        for (DatabaseInstance d : databaseService.list()) {
            ObjectNode n = Json.MAPPER.valueToTree(d);
            n.put("credential", credNames.get(d.getCredentialId()));
            ObjectNode collector = (ObjectNode) n.get("collector");
            if (collector != null) {
                collector.remove("credentialId");
                collector.put("credential", credNames.get(d.getCollector().getCredentialId()));
            }
            dbn.add(n);
        }
        ArrayNode dsn = root.putArray("datasources");
        for (Datasource d : datasourceService.list()) {
            ObjectNode n = Json.MAPPER.valueToTree(d);
            n.put("ownerTeam", teamNames.get(d.getOwnerTeamId()));
            n.put("currentDatabase", dbNames.get(d.getCurrentDatabaseId()));
            n.put("targetDatabase", dbNames.get(d.getTargetDatabaseId()));
            ArrayNode rules = (ArrayNode) n.get("routingRules");
            if (rules != null) for (int i = 0; i < rules.size(); i++) {
                ObjectNode r = (ObjectNode) rules.get(i);
                r.put("application", appNames.get(d.getRoutingRules().get(i).getApplicationId()));
                r.put("database", dbNames.get(d.getRoutingRules().get(i).getDatabaseId()));
            }
            dsn.add(n);
        }
        ArrayNode gn = root.putArray("accessGrants");
        for (AccessGrant g : grants.findAll()) {
            ObjectNode n = Json.MAPPER.valueToTree(g);
            n.put("application", appNames.get(g.getApplicationId()));
            n.put("datasource", dsNames.get(g.getDatasourceId()));
            gn.add(n);
        }
        ArrayNode on = root.putArray("ownership");
        for (SchemaOwnership so : catalogue.schemaOwnerships()) {
            ObjectNode n = on.addObject();
            n.put("database", dbNames.get(so.getDatabaseId()));
            n.put("schema", so.getSchema());
            n.putNull("table");
            n.put("team", teamNames.get(so.getTeamId()));
            n.put("confirmed", so.isConfirmed());
        }
        ArrayNode pn = root.putArray("producers");
        ArrayNode tbn = root.putArray("tables");
        for (DbTable t : tableMap.values().stream().sorted((a, b) -> (a.getSchema() + a.getName()).compareTo(b.getSchema() + b.getName())).toList()) {
            if (t.getOwnerTeamId() != null && t.getOwnerSource() == Enums.OwnerSource.DECLARED) {
                ObjectNode n = on.addObject();
                n.put("database", dbNames.get(t.getDatabaseId()));
                n.put("schema", t.getSchema());
                n.put("table", t.getName());
                n.put("team", teamNames.get(t.getOwnerTeamId()));
                n.put("confirmed", t.isOwnerConfirmed());
            }
            if (t.getProducerApplicationId() != null && t.getProducerSource() == Enums.OwnerSource.DECLARED) {
                ObjectNode n = pn.addObject();
                n.put("database", dbNames.get(t.getDatabaseId()));
                n.put("schema", t.getSchema());
                n.put("table", t.getName());
                n.put("application", appNames.get(t.getProducerApplicationId()));
            }
            if (t.getClassification() != null || t.getDescription() != null || !t.getTags().isEmpty() || t.getMigration().getState() != Enums.MigrationState.NOT_PLANNED) {
                ObjectNode n = tbn.addObject();
                n.put("database", dbNames.get(t.getDatabaseId()));
                n.put("schema", t.getSchema());
                n.put("table", t.getName());
                n.put("kind", t.getKind().name());
                n.put("classification", t.getClassification() == null ? null : t.getClassification().name());
                n.put("description", t.getDescription());
                n.set("tags", Json.MAPPER.valueToTree(t.getTags()));
                ObjectNode m = n.putObject("migration");
                m.put("targetDatabase", dbNames.get(t.getMigration().getTargetDatabaseId()));
                m.put("targetSchema", t.getMigration().getTargetSchema());
                m.put("targetName", t.getMigration().getTargetName());
                m.put("state", t.getMigration().getState().name());
            }
        }
        ArrayNode rn = root.putArray("relationships");
        for (Relationship r : relationships.findAll()) {
            if (r.getSource() != Enums.RelationshipSource.DECLARED) continue;
            ObjectNode n = rn.addObject();
            n.put("application", appNames.get(r.getApplicationId()));
            putObjectRef(n, r.getObjectType(), r.getObjectId(), "object", tableMap, routineMap, dbNames);
            n.put("objectType", r.getObjectType().name());
            n.put("kind", r.getKind().name());
            n.put("source", "DECLARED");
            n.put("confirmed", r.isConfirmed());
        }
        ArrayNode dn = root.putArray("dependencies");
        for (Dependency d : dependencies.findAll()) {
            if (d.getSource() != Enums.DependencySource.DECLARED) continue;
            ObjectNode n = dn.addObject();
            n.put("fromType", d.getFromType().name());
            putObjectRef(n.putObject("from"), d.getFromType(), d.getFromId(), "name", tableMap, routineMap, dbNames);
            n.put("toType", d.getToType().name());
            putObjectRef(n.putObject("to"), d.getToType(), d.getToId(), "name", tableMap, routineMap, dbNames);
            n.put("kind", d.getKind().name());
        }
        return root;
    }

    private static void putObjectRef(ObjectNode n, Enums.ObjectType type, String id, String nameField, Map<String, DbTable> tableMap, Map<String, Routine> routineMap, Map<String, String> dbNames) {
        if (type == Enums.ObjectType.TABLE) {
            DbTable t = tableMap.get(id);
            if (t != null) { n.put("database", dbNames.get(t.getDatabaseId())); n.put("schema", t.getSchema()); n.put(nameField, t.getName()); }
        } else {
            Routine r = routineMap.get(id);
            if (r != null) { n.put("database", dbNames.get(r.getDatabaseId())); n.put("schema", r.getSchema()); n.put(nameField, r.getName()); }
        }
    }

    // ---- import ----------------------------------------------------------------------------------

    public record ImportResult(int teams, int applications, int credentials, int databases, int datasources, int accessGrants, int ownership,
                               int producers, int tables, int relationships, int dependencies) {}

    @Transactional
    public ImportResult importDocument(JsonNode doc) {
        if (doc == null || !doc.isObject()) throw new ApiException.BadRequest("import document must be a JSON object");
        int nTeams = 0, nApps = 0, nCreds = 0, nDbs = 0, nDs = 0, nGrants = 0, nOwn = 0, nProd = 0, nTbl = 0, nRel = 0, nDep = 0;
        for (JsonNode n : arr(doc, "teams")) { teamService.upsertByName(Json.MAPPER.convertValue(n, Team.class)); nTeams++; }
        for (JsonNode n : arr(doc, "credentials")) { credentialService.upsertByName(Json.MAPPER.convertValue(n, CredentialRequest.class)); nCreds++; }
        for (JsonNode n : arr(doc, "applications")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("teamId", ref(o, teamResolver(), "team", "teamName", "teamId"));
            applicationService.upsertByName(Json.MAPPER.convertValue(o, Application.class));
            nApps++;
        }
        for (JsonNode n : arr(doc, "databases")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("credentialId", ref(o, credentialResolver(), "credential", "credentialName", "credentialId"));
            if (o.get("collector") instanceof ObjectNode collector) {
                collector.put("credentialId", ref(collector, credentialResolver(), "credential", "credentialName", "credentialId"));
            }
            databaseService.upsertByName(Json.MAPPER.convertValue(o, DatabaseInstance.class));
            nDbs++;
        }
        for (JsonNode n : arr(doc, "datasources")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("ownerTeamId", ref(o, teamResolver(), "ownerTeam", "ownerTeamName", "ownerTeamId"));
            o.put("currentDatabaseId", ref(o, databaseResolver(), "currentDatabase", "currentDatabaseName", "currentDatabaseId"));
            o.put("targetDatabaseId", ref(o, databaseResolver(), "targetDatabase", "targetDatabaseName", "targetDatabaseId"));
            JsonNode rules = o.get("routingRules");
            if (rules != null && rules.isArray()) for (JsonNode rn : rules) {
                ObjectNode r = (ObjectNode) rn;
                r.put("applicationId", ref(r, applicationResolver(), "application", "applicationName", "applicationId"));
                r.put("databaseId", ref(r, databaseResolver(), "database", "databaseName", "databaseId"));
                r.remove("id");
            }
            Datasource ds = Json.MAPPER.convertValue(o, Datasource.class);
            if (rules == null) ds.setRoutingRules(null);
            datasourceService.upsertByName(ds);
            nDs++;
        }
        for (JsonNode n : arr(doc, "accessGrants")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("applicationId", ref(o, applicationResolver(), "application", "applicationName", "applicationId"));
            o.put("datasourceId", ref(o, datasourceResolver(), "datasource", "datasourceName", "datasourceId"));
            AccessGrant g = Json.MAPPER.convertValue(o, AccessGrant.class);
            if (g.getApplicationId() == null || g.getDatasourceId() == null) throw new ApiException.BadRequest("accessGrant references an unknown application or datasource: " + n);
            grantService.upsert(g);
            nGrants++;
        }
        for (JsonNode n : arr(doc, "ownership")) {
            DatabaseInstance db = databaseOf(n);
            String teamId = ref((ObjectNode) n, teamResolver(), "team", "ownerTeamName", "ownerTeamId", "teamName", "teamId");
            if (db == null || teamId == null) throw new ApiException.BadRequest("ownership entry references an unknown database or team: " + n);
            boolean confirmed = !n.has("confirmed") ? !n.has("ownerConfirmed") || n.get("ownerConfirmed").asBoolean(true) : n.get("confirmed").asBoolean(true);
            String tableName = text(n, "table", "name");
            String schema = text(n, "schema");
            if (tableName == null) {
                catalogue.setSchemaOwnership(db.getId(), schema, teamId, confirmed);
            } else {
                DbTable t = tableOrPlaceholder(db, schema, tableName, n.path("kind").asText(null));
                t.setOwnerTeamId(teamId); t.setOwnerSource(Enums.OwnerSource.DECLARED); t.setOwnerConfirmed(confirmed);
                String producer = ref((ObjectNode) n, applicationResolver(), "producer", "producerApplicationName", "producerApplicationId");
                if (producer != null) { t.setProducerApplicationId(producer); t.setProducerSource(Enums.OwnerSource.DECLARED); }
                tables.save(t);
            }
            nOwn++;
        }
        for (JsonNode n : arr(doc, "producers")) {
            DatabaseInstance db = databaseOf(n);
            String appId = ref((ObjectNode) n, applicationResolver(), "application", "applicationName", "applicationId");
            if (db == null || appId == null) throw new ApiException.BadRequest("producer entry references an unknown database or application: " + n);
            DbTable t = tableOrPlaceholder(db, text(n, "schema"), text(n, "table", "name"), null);
            t.setProducerApplicationId(appId); t.setProducerSource(Enums.OwnerSource.DECLARED);
            tables.save(t);
            nProd++;
        }
        for (JsonNode n : arr(doc, "tables")) {
            DatabaseInstance db = databaseOf(n);
            if (db == null) continue;
            DbTable t = tableOrPlaceholder(db, text(n, "schema"), text(n, "table", "name"), n.path("kind").asText(null));
            if (n.hasNonNull("classification")) t.setClassification(Enums.Classification.valueOf(n.get("classification").asText()));
            if (n.hasNonNull("description")) t.setDescription(n.get("description").asText());
            if (n.has("tags") && n.get("tags").isArray()) t.setTags(Json.MAPPER.convertValue(n.get("tags"), Json.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
            if (n.get("migration") instanceof ObjectNode m) {
                TableMigration tm = new TableMigration();
                tm.setTargetDatabaseId(ref(m, databaseResolver(), "targetDatabase", "targetDatabaseName", "targetDatabaseId"));
                tm.setTargetSchema(m.path("targetSchema").asText(null));
                tm.setTargetName(m.path("targetName").asText(null));
                if (m.hasNonNull("state")) tm.setState(Enums.MigrationState.valueOf(m.get("state").asText()));
                t.setMigration(tm);
            }
            tables.save(t);
            nTbl++;
        }
        for (JsonNode n : arr(doc, "relationships")) {
            String appId = ref((ObjectNode) n, applicationResolver(), "application", "applicationName", "applicationId");
            DatabaseInstance db = databaseOf(n);
            if (appId == null || db == null) throw new ApiException.BadRequest("relationship references an unknown application or database: " + n);
            Enums.ObjectType type = Enums.ObjectType.valueOf(n.path("objectType").asText("TABLE").toUpperCase());
            String objectName = text(n, "object", "name", "table", "routine");
            String objectId = type == Enums.ObjectType.TABLE ? tableOrPlaceholder(db, text(n, "schema"), objectName, null).getId()
                    : routineOrPlaceholder(db, text(n, "schema"), objectName).getId();
            Relationship r = new Relationship();
            r.setApplicationId(appId); r.setObjectType(type); r.setObjectId(objectId);
            r.setKind(Enums.RelationshipKind.valueOf(n.path("kind").asText("READS").toUpperCase()));
            Relationship saved = catalogue.declareRelationship(r);
            if (n.has("confirmed")) catalogue.setRelationshipConfirmed(saved.getId(), n.get("confirmed").asBoolean(true));
            nRel++;
        }
        for (JsonNode n : arr(doc, "dependencies")) {
            Enums.ObjectType ft = Enums.ObjectType.valueOf(n.path("fromType").asText("ROUTINE").toUpperCase());
            Enums.ObjectType tt = Enums.ObjectType.valueOf(n.path("toType").asText("TABLE").toUpperCase());
            DatabaseInstance fdb = databaseOf(n.get("from")), tdb = databaseOf(n.get("to"));
            if (fdb == null || tdb == null) continue;
            String from = ft == Enums.ObjectType.TABLE ? tableOrPlaceholder(fdb, text(n.get("from"), "schema"), text(n.get("from"), "name", "table"), null).getId()
                    : routineOrPlaceholder(fdb, text(n.get("from"), "schema"), text(n.get("from"), "name", "routine")).getId();
            String to = tt == Enums.ObjectType.TABLE ? tableOrPlaceholder(tdb, text(n.get("to"), "schema"), text(n.get("to"), "name", "table"), null).getId()
                    : routineOrPlaceholder(tdb, text(n.get("to"), "schema"), text(n.get("to"), "name", "routine")).getId();
            Dependency d = new Dependency();
            d.setFromType(ft); d.setFromId(from); d.setToType(tt); d.setToId(to);
            d.setKind(Enums.DependencyKind.valueOf(n.path("kind").asText("REFERENCES").toUpperCase()));
            catalogue.declareDependency(d);
            nDep++;
        }
        return new ImportResult(nTeams, nApps, nCreds, nDbs, nDs, nGrants, nOwn, nProd, nTbl, nRel, nDep);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private DbTable tableOrPlaceholder(DatabaseInstance db, String schema, String name, String kind) {
        if (schema == null || name == null) throw new ApiException.BadRequest("schema and table name are required (" + db.getName() + ")");
        DbTable t = tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), schema, name).orElseGet(() -> {
            DbTable n = new DbTable();
            n.setId(Ids.newId()); n.setDatabaseId(db.getId()); n.setSchema(schema); n.setName(name);
            n.setDiscovered(true); n.setFirstSeenAt(Instant.now());
            return n;
        });
        if (kind != null && !kind.isBlank()) t.setKind(Enums.TableKind.valueOf(kind.toUpperCase()));
        catalogue.applySchemaOwnership(t);
        return tables.save(t);
    }

    private Routine routineOrPlaceholder(DatabaseInstance db, String schema, String name) {
        if (schema == null || name == null) throw new ApiException.BadRequest("schema and routine name are required (" + db.getName() + ")");
        return routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), schema, name).orElseGet(() -> {
            Routine n = new Routine();
            n.setId(Ids.newId()); n.setDatabaseId(db.getId()); n.setSchema(schema); n.setName(name);
            n.setKind(Enums.RoutineKind.PROCEDURE); n.setDiscovered(true); n.setFirstSeenAt(Instant.now());
            return routines.save(n);
        });
    }

    private DatabaseInstance databaseOf(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String id = ref((ObjectNode) n, databaseResolver(), "database", "databaseName", "databaseId");
        return id == null ? null : databases.findById(id).orElse(null);
    }

    private static String text(JsonNode n, String... fields) {
        for (String f : fields) if (n.hasNonNull(f) && !n.get(f).asText().isBlank()) return n.get(f).asText();
        return null;
    }

    private static Iterable<JsonNode> arr(JsonNode doc, String field) {
        JsonNode n = doc.get(field);
        return n != null && n.isArray() ? n : List.of();
    }

    private record Resolver(Function<String, Optional<String>> byName, Predicate<String> idExists) {}

    private Resolver teamResolver() { return new Resolver(name -> teams.findByName(name).map(Team::getId), teams::existsById); }
    private Resolver applicationResolver() { return new Resolver(name -> applications.findByName(name).map(Application::getId), applications::existsById); }
    private Resolver credentialResolver() { return new Resolver(name -> credentials.findByName(name).map(Credential::getId), credentials::existsById); }
    private Resolver databaseResolver() { return new Resolver(name -> databases.findByName(name).map(DatabaseInstance::getId), databases::existsById); }
    private Resolver datasourceResolver() { return new Resolver(name -> datasources.findByName(name).map(Datasource::getId), datasources::existsById); }

    /** Resolves a reference by name (any of the given fields, in order), then by id; null when nothing resolves. */
    private static String ref(ObjectNode o, Resolver resolver, String... fields) {
        for (String f : fields) {
            if (!o.hasNonNull(f)) continue;
            String v = o.get(f).asText();
            if (v.isBlank()) continue;
            Optional<String> byName = resolver.byName().apply(v);
            if (byName.isPresent()) return byName.get();
            if (resolver.idExists().test(v)) return v;
        }
        return null;
    }
}
