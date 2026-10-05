package org.dbplatform.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.domain.RoutingRule;
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
 * {@code GET /export} / {@code POST /import}: the whole configuration as one JSON document. Entities are
 * exported with their ids plus {@code *Name} references so a document can be imported into another
 * control plane (upsert by name, references resolved by name first, then by id). Secrets are never exported.
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
            n.put("teamName", teamNames.get(a.getTeamId()));
            an.add(n);
        }
        ArrayNode cn = root.putArray("credentials");
        credentialService.list().forEach(c -> cn.add(Json.MAPPER.valueToTree(c)));
        ArrayNode dbn = root.putArray("databases");
        for (DatabaseInstance d : databaseService.list()) {
            ObjectNode n = Json.MAPPER.valueToTree(d);
            n.put("credentialName", credNames.get(d.getCredentialId()));
            dbn.add(n);
        }
        ArrayNode dsn = root.putArray("datasources");
        for (Datasource d : datasourceService.list()) {
            ObjectNode n = Json.MAPPER.valueToTree(d);
            n.put("ownerTeamName", teamNames.get(d.getOwnerTeamId()));
            n.put("currentDatabaseName", dbNames.get(d.getCurrentDatabaseId()));
            n.put("targetDatabaseName", dbNames.get(d.getTargetDatabaseId()));
            ArrayNode rules = (ArrayNode) n.get("routingRules");
            if (rules != null) for (int i = 0; i < rules.size(); i++) {
                ObjectNode r = (ObjectNode) rules.get(i);
                r.put("applicationName", appNames.get(d.getRoutingRules().get(i).getApplicationId()));
                r.put("databaseName", dbNames.get(d.getRoutingRules().get(i).getDatabaseId()));
            }
            dsn.add(n);
        }
        ArrayNode gn = root.putArray("accessGrants");
        for (AccessGrant g : grants.findAll()) {
            ObjectNode n = Json.MAPPER.valueToTree(g);
            n.put("applicationName", appNames.get(g.getApplicationId()));
            n.put("datasourceName", dsNames.get(g.getDatasourceId()));
            gn.add(n);
        }
        ArrayNode on = root.putArray("ownership");
        for (DbTable t : tableMap.values()) {
            if (t.getOwnerTeamId() == null && t.getProducerApplicationId() == null && t.getClassification() == null && t.getDescription() == null && t.getTags().isEmpty()) continue;
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.put("databaseName", dbNames.get(t.getDatabaseId()));
            n.put("schema", t.getSchema());
            n.put("name", t.getName());
            n.put("kind", t.getKind().name());
            n.put("ownerTeamName", teamNames.get(t.getOwnerTeamId()));
            n.put("ownerConfirmed", t.isOwnerConfirmed());
            n.put("ownerSource", t.getOwnerSource().name());
            n.put("producerApplicationName", appNames.get(t.getProducerApplicationId()));
            n.put("classification", t.getClassification() == null ? null : t.getClassification().name());
            n.put("description", t.getDescription());
            n.set("tags", Json.MAPPER.valueToTree(t.getTags()));
            n.set("migration", Json.MAPPER.valueToTree(t.getMigration()));
            on.add(n);
        }
        ArrayNode rn = root.putArray("relationships");
        for (Relationship r : relationships.findAll()) {
            if (r.getSource() != Enums.RelationshipSource.DECLARED) continue;
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.put("applicationName", appNames.get(r.getApplicationId()));
            n.put("objectType", r.getObjectType().name());
            putObjectRef(n, r.getObjectType(), r.getObjectId(), tableMap, routineMap, dbNames);
            n.put("kind", r.getKind().name());
            rn.add(n);
        }
        ArrayNode dn = root.putArray("dependencies");
        for (Dependency d : dependencies.findAll()) {
            if (d.getSource() != Enums.DependencySource.DECLARED) continue;
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.put("fromType", d.getFromType().name());
            putObjectRef(n.putObject("from"), d.getFromType(), d.getFromId(), tableMap, routineMap, dbNames);
            n.put("toType", d.getToType().name());
            putObjectRef(n.putObject("to"), d.getToType(), d.getToId(), tableMap, routineMap, dbNames);
            n.put("kind", d.getKind().name());
            dn.add(n);
        }
        return root;
    }

    private static void putObjectRef(ObjectNode n, Enums.ObjectType type, String id, Map<String, DbTable> tableMap, Map<String, Routine> routineMap, Map<String, String> dbNames) {
        if (type == Enums.ObjectType.TABLE) {
            DbTable t = tableMap.get(id);
            if (t != null) { n.put("databaseName", dbNames.get(t.getDatabaseId())); n.put("schema", t.getSchema()); n.put("name", t.getName()); }
        } else {
            Routine r = routineMap.get(id);
            if (r != null) { n.put("databaseName", dbNames.get(r.getDatabaseId())); n.put("schema", r.getSchema()); n.put("name", r.getName()); }
        }
    }

    public record ImportResult(int teams, int applications, int credentials, int databases, int datasources, int accessGrants, int ownership, int relationships, int dependencies) {}

    @Transactional
    public ImportResult importDocument(JsonNode doc) {
        if (doc == null || !doc.isObject()) throw new ApiException.BadRequest("import document must be a JSON object");
        int nTeams = 0, nApps = 0, nCreds = 0, nDbs = 0, nDs = 0, nGrants = 0, nOwn = 0, nRel = 0, nDep = 0;
        for (JsonNode n : arr(doc, "teams")) { teamService.upsertByName(Json.MAPPER.convertValue(n, Team.class)); nTeams++; }
        for (JsonNode n : arr(doc, "credentials")) {
            CredentialRequest r = Json.MAPPER.convertValue(n, CredentialRequest.class);
            credentialService.upsertByName(r);
            nCreds++;
        }
        for (JsonNode n : arr(doc, "applications")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("teamId", resolveId(o, "teamName", "teamId", name -> teams.findByName(name).map(Team::getId), id -> teams.existsById(id)));
            applicationService.upsertByName(Json.MAPPER.convertValue(o, Application.class));
            nApps++;
        }
        for (JsonNode n : arr(doc, "databases")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("credentialId", resolveId(o, "credentialName", "credentialId", name -> credentials.findByName(name).map(Credential::getId), id -> credentials.existsById(id)));
            databaseService.upsertByName(Json.MAPPER.convertValue(o, DatabaseInstance.class));
            nDbs++;
        }
        for (JsonNode n : arr(doc, "datasources")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("ownerTeamId", resolveId(o, "ownerTeamName", "ownerTeamId", name -> teams.findByName(name).map(Team::getId), id -> teams.existsById(id)));
            o.put("currentDatabaseId", resolveId(o, "currentDatabaseName", "currentDatabaseId", name -> databases.findByName(name).map(DatabaseInstance::getId), id -> databases.existsById(id)));
            o.put("targetDatabaseId", resolveId(o, "targetDatabaseName", "targetDatabaseId", name -> databases.findByName(name).map(DatabaseInstance::getId), id -> databases.existsById(id)));
            JsonNode rules = o.get("routingRules");
            if (rules != null && rules.isArray()) for (JsonNode rn : rules) {
                ObjectNode r = (ObjectNode) rn;
                r.put("applicationId", resolveId(r, "applicationName", "applicationId", name -> applications.findByName(name).map(Application::getId), id -> applications.existsById(id)));
                r.put("databaseId", resolveId(r, "databaseName", "databaseId", name -> databases.findByName(name).map(DatabaseInstance::getId), id -> databases.existsById(id)));
                r.remove("id");
            }
            Datasource ds = Json.MAPPER.convertValue(o, Datasource.class);
            if (rules == null) ds.setRoutingRules(null);
            datasourceService.upsertByName(ds);
            nDs++;
        }
        for (JsonNode n : arr(doc, "accessGrants")) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            o.put("applicationId", resolveId(o, "applicationName", "applicationId", name -> applications.findByName(name).map(Application::getId), id -> applications.existsById(id)));
            o.put("datasourceId", resolveId(o, "datasourceName", "datasourceId", name -> datasources.findByName(name).map(Datasource::getId), id -> datasources.existsById(id)));
            AccessGrant g = Json.MAPPER.convertValue(o, AccessGrant.class);
            if (g.getApplicationId() == null || g.getDatasourceId() == null) throw new ApiException.BadRequest("accessGrant references unknown application/datasource: " + n);
            grantService.upsert(g);
            nGrants++;
        }
        for (JsonNode n : arr(doc, "ownership")) {
            Optional<DbTable> t = findTable(n);
            if (t.isEmpty()) continue;
            DbTable table = t.get();
            String owner = resolveId((ObjectNode) n, "ownerTeamName", "ownerTeamId", name -> teams.findByName(name).map(Team::getId), id -> teams.existsById(id));
            if (owner != null) { table.setOwnerTeamId(owner); table.setOwnerSource(Enums.OwnerSource.DECLARED); table.setOwnerConfirmed(!n.has("ownerConfirmed") || n.get("ownerConfirmed").asBoolean()); }
            String producer = resolveId((ObjectNode) n, "producerApplicationName", "producerApplicationId", name -> applications.findByName(name).map(Application::getId), id -> applications.existsById(id));
            if (producer != null) { table.setProducerApplicationId(producer); table.setProducerSource(Enums.OwnerSource.DECLARED); }
            if (n.hasNonNull("classification")) table.setClassification(Enums.Classification.valueOf(n.get("classification").asText()));
            if (n.hasNonNull("description")) table.setDescription(n.get("description").asText());
            if (n.has("tags") && n.get("tags").isArray()) table.setTags(Json.MAPPER.convertValue(n.get("tags"), Json.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
            if (n.hasNonNull("migration")) table.setMigration(Json.MAPPER.convertValue(n.get("migration"), org.dbplatform.controlplane.domain.TableMigration.class));
            tables.save(table);
            nOwn++;
        }
        for (JsonNode n : arr(doc, "relationships")) {
            Optional<Application> app = applications.findByName(n.path("applicationName").asText(null));
            Enums.ObjectType type = Enums.ObjectType.valueOf(n.path("objectType").asText("TABLE"));
            Optional<String> objectId = type == Enums.ObjectType.TABLE ? findTable(n).map(DbTable::getId) : findRoutine(n).map(Routine::getId);
            if (app.isEmpty() || objectId.isEmpty()) continue;
            Relationship r = new Relationship();
            r.setApplicationId(app.get().getId()); r.setObjectType(type); r.setObjectId(objectId.get());
            r.setKind(Enums.RelationshipKind.valueOf(n.path("kind").asText("READS")));
            catalogue.declareRelationship(r);
            nRel++;
        }
        for (JsonNode n : arr(doc, "dependencies")) {
            Enums.ObjectType ft = Enums.ObjectType.valueOf(n.path("fromType").asText("ROUTINE"));
            Enums.ObjectType tt = Enums.ObjectType.valueOf(n.path("toType").asText("TABLE"));
            Optional<String> from = ft == Enums.ObjectType.TABLE ? findTable(n.get("from")).map(DbTable::getId) : findRoutine(n.get("from")).map(Routine::getId);
            Optional<String> to = tt == Enums.ObjectType.TABLE ? findTable(n.get("to")).map(DbTable::getId) : findRoutine(n.get("to")).map(Routine::getId);
            if (from.isEmpty() || to.isEmpty()) continue;
            Dependency d = new Dependency();
            d.setFromType(ft); d.setFromId(from.get()); d.setToType(tt); d.setToId(to.get());
            d.setKind(Enums.DependencyKind.valueOf(n.path("kind").asText("REFERENCES")));
            catalogue.declareDependency(d);
            nDep++;
        }
        return new ImportResult(nTeams, nApps, nCreds, nDbs, nDs, nGrants, nOwn, nRel, nDep);
    }

    private Optional<DbTable> findTable(JsonNode n) {
        if (n == null || n.isNull()) return Optional.empty();
        return databases.findByName(n.path("databaseName").asText(null))
                .flatMap(db -> tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), n.path("schema").asText(null), n.path("name").asText(null)));
    }

    private Optional<Routine> findRoutine(JsonNode n) {
        if (n == null || n.isNull()) return Optional.empty();
        return databases.findByName(n.path("databaseName").asText(null))
                .flatMap(db -> routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), n.path("schema").asText(null), n.path("name").asText(null)));
    }

    private static Iterable<JsonNode> arr(JsonNode doc, String field) {
        JsonNode n = doc.get(field);
        return n != null && n.isArray() ? n : List.of();
    }

    /** Resolves a reference by name first, then by id; returns null when neither resolves. */
    private static String resolveId(ObjectNode o, String nameField, String idField, java.util.function.Function<String, Optional<String>> byName,
                                    java.util.function.Predicate<String> idExists) {
        String name = o.hasNonNull(nameField) ? o.get(nameField).asText() : null;
        if (name != null && !name.isBlank()) {
            Optional<String> id = byName.apply(name);
            if (id.isPresent()) return id.get();
        }
        String id = o.hasNonNull(idField) ? o.get(idField).asText() : null;
        return id != null && !id.isBlank() && idExists.test(id) ? id : null;
    }

    /** Exposed for tests / seed. */
    Map<String, Object> asMap(JsonNode n) { return Json.MAPPER.convertValue(n, LinkedHashMap.class); }
}
