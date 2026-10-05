package org.dbplatform.controlplane.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dbplatform.controlplane.api.dto.RoutineUpdateRequest;
import org.dbplatform.controlplane.api.dto.TableUpdateRequest;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.DbColumn;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.DependencyKind;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DbColumnRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.DependencyRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.SchemaOwnershipRepository;
import org.dbplatform.controlplane.domain.SchemaOwnership;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalogue: tables, columns, routines, static dependencies and application relationships. */
@Service
@Transactional
public class CatalogueService {
    private final DbTableRepository tables;
    private final DbColumnRepository columns;
    private final RoutineRepository routines;
    private final DependencyRepository dependencies;
    private final RelationshipRepository relationships;
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DatabaseRepository databases;
    private final SchemaOwnershipRepository schemaOwnership;

    public CatalogueService(DbTableRepository tables, DbColumnRepository columns, RoutineRepository routines,
                            DependencyRepository dependencies, RelationshipRepository relationships, TeamRepository teams,
                            ApplicationRepository applications, DatabaseRepository databases, SchemaOwnershipRepository schemaOwnership) {
        this.tables = tables; this.columns = columns; this.routines = routines; this.dependencies = dependencies;
        this.relationships = relationships; this.teams = teams; this.applications = applications; this.databases = databases;
        this.schemaOwnership = schemaOwnership;
    }

    /** Applies the schema-wide ownership default (if any) to a table that has no owner yet. */
    public void applySchemaOwnership(DbTable t) {
        if (t.getOwnerTeamId() != null) return;
        schemaOwnership.findByDatabaseIdAndSchemaIgnoreCase(t.getDatabaseId(), t.getSchema()).ifPresent(so -> {
            t.setOwnerTeamId(so.getTeamId());
            t.setOwnerSource(Enums.OwnerSource.DECLARED);
            t.setOwnerConfirmed(so.isConfirmed());
        });
    }

    /** Records a schema-wide ownership rule and applies it to the existing tables of the schema. */
    public int setSchemaOwnership(String databaseId, String schema, String teamId, boolean confirmed) {
        databases.findById(databaseId).orElseThrow(() -> new ApiException.NotFound("Database", databaseId));
        teams.findById(teamId).orElseThrow(() -> new ApiException.BadRequest("Unknown teamId '" + teamId + "'"));
        SchemaOwnership so = schemaOwnership.findByDatabaseIdAndSchemaIgnoreCase(databaseId, schema).orElseGet(() -> {
            SchemaOwnership n = new SchemaOwnership();
            n.setId(Ids.newId()); n.setDatabaseId(databaseId); n.setSchema(schema);
            return n;
        });
        so.setTeamId(teamId);
        so.setConfirmed(confirmed);
        schemaOwnership.save(so);
        List<DbTable> list = tables.findByDatabaseIdAndSchemaIgnoreCase(databaseId, schema);
        for (DbTable t : list) {
            t.setOwnerTeamId(teamId); t.setOwnerSource(Enums.OwnerSource.DECLARED); t.setOwnerConfirmed(confirmed);
        }
        tables.saveAll(list);
        return list.size();
    }

    public List<SchemaOwnership> schemaOwnerships() { return schemaOwnership.findAll(); }

    // ---- tables ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DbTable> searchTables(String databaseId, String schema, String q, String ownerTeamId, boolean unowned) {
        return searchTables(databaseId, schema, q, ownerTeamId, unowned, null);
    }

    @Transactional(readOnly = true)
    public List<DbTable> searchTables(String databaseId, String schema, String q, String ownerTeamId, boolean unowned, String classification) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        List<DbTable> found = tables.search(blankToNull(databaseId), blankToNull(schema), blankToNull(ownerTeamId), unowned, like);
        if (classification == null || classification.isBlank()) return found;
        Enums.Classification wanted;
        try {
            wanted = Enums.Classification.valueOf(classification.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException.BadRequest("classification must be one of PII, CONFIDENTIAL, INTERNAL, PUBLIC");
        }
        return found.stream().filter(t -> t.getClassification() == wanted).toList();
    }

    @Transactional(readOnly = true)
    public DbTable getTable(String id) { return tables.findById(id).orElseThrow(() -> new ApiException.NotFound("Table", id)); }

    public DbTable updateTable(String id, TableUpdateRequest in) {
        DbTable t = getTable(id);
        if (in.ownerTeamId() != null) {
            if (in.ownerTeamId().isBlank()) {
                t.setOwnerTeamId(null); t.setOwnerSource(Enums.OwnerSource.NONE); t.setOwnerConfirmed(false);
            } else {
                teams.findById(in.ownerTeamId()).orElseThrow(() -> new ApiException.BadRequest("Unknown ownerTeamId '" + in.ownerTeamId() + "'"));
                t.setOwnerTeamId(in.ownerTeamId()); t.setOwnerSource(Enums.OwnerSource.DECLARED);
                t.setOwnerConfirmed(in.ownerConfirmed() == null || in.ownerConfirmed());
            }
        } else if (in.ownerConfirmed() != null) {
            t.setOwnerConfirmed(in.ownerConfirmed());
        }
        if (in.producerApplicationId() != null) {
            if (in.producerApplicationId().isBlank()) {
                t.setProducerApplicationId(null); t.setProducerSource(null);
            } else {
                applications.findById(in.producerApplicationId()).orElseThrow(() -> new ApiException.BadRequest("Unknown producerApplicationId '" + in.producerApplicationId() + "'"));
                t.setProducerApplicationId(in.producerApplicationId()); t.setProducerSource(Enums.OwnerSource.DECLARED);
            }
        }
        if (in.description() != null) t.setDescription(blankToNull(in.description()));
        if (in.tags() != null) t.setTags(in.tags());
        if (in.classification() != null) t.setClassification(in.classification().isBlank() ? null : Enums.Classification.valueOf(in.classification()));
        if (in.migration() != null) t.setMigration(in.migration());
        return tables.save(t);
    }

    public DbTable setOwnership(String id, String teamId, Boolean confirmed) {
        DbTable t = getTable(id);
        if (teamId == null || teamId.isBlank()) {
            t.setOwnerTeamId(null); t.setOwnerSource(Enums.OwnerSource.NONE); t.setOwnerConfirmed(false);
        } else {
            teams.findById(teamId).orElseThrow(() -> new ApiException.BadRequest("Unknown teamId '" + teamId + "'"));
            t.setOwnerTeamId(teamId);
            t.setOwnerSource(Enums.OwnerSource.DECLARED);
            t.setOwnerConfirmed(confirmed == null || confirmed);
        }
        return tables.save(t);
    }

    public int bulkOwnership(String databaseId, String schema, String teamId) {
        return setSchemaOwnership(databaseId, schema, teamId, true);
    }

    @Transactional(readOnly = true)
    public List<DbColumn> columns(String tableId) {
        getTable(tableId);
        return columns.findByTableIdOrderByPositionAsc(tableId);
    }

    public DbColumn updateColumn(String tableId, String columnId, String comment, String classification) {
        DbColumn c = columns.findById(columnId).filter(x -> x.getTableId().equals(tableId)).orElseThrow(() -> new ApiException.NotFound("Column", columnId));
        if (comment != null) c.setComment(comment.isBlank() ? null : comment);
        if (classification != null) c.setClassification(classification.isBlank() ? null : Enums.Classification.valueOf(classification));
        return columns.save(c);
    }

    @Transactional(readOnly = true)
    public DbColumn getColumn(String columnId) { return columns.findById(columnId).orElseThrow(() -> new ApiException.NotFound("Column", columnId)); }

    /**
     * Finds a table of a database by name as written in SQL. Resolution order for an unqualified name:
     * the session's {@code defaultSchema}, then the database's configured {@code collector.schemas}
     * (unique match), then a unique match by name across all schemas.
     */
    @Transactional(readOnly = true)
    public Optional<DbTable> findTable(String databaseId, String schema, String name, String defaultSchema) {
        return findTable(databaseId, schema, name, defaultSchema, databases.findById(databaseId).map(d -> d.getCollector().getSchemas()).orElse(List.of()));
    }

    @Transactional(readOnly = true)
    public Optional<DbTable> findTable(String databaseId, String schema, String name, String defaultSchema, List<String> configuredSchemas) {
        if (name == null) return Optional.empty();
        String n = unquote(name);
        if (n.contains(".") && schema == null) {
            String[] parts = n.split("\\.", 2);
            schema = parts[0];
            n = parts[1];
        }
        if (schema != null) return tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(databaseId, unquote(schema), n);
        if (defaultSchema != null) {
            Optional<DbTable> t = tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(databaseId, unquote(defaultSchema), n);
            if (t.isPresent()) return t;
        }
        List<DbTable> byName = tables.findByDatabaseIdAndNameIgnoreCase(databaseId, n);
        if (configuredSchemas != null && !configuredSchemas.isEmpty()) {
            List<DbTable> inConfigured = byName.stream().filter(t -> configuredSchemas.stream().anyMatch(cs -> cs.equalsIgnoreCase(t.getSchema()))).toList();
            if (inConfigured.size() == 1) return Optional.of(inConfigured.get(0));
        }
        return byName.size() == 1 ? Optional.of(byName.get(0)) : Optional.empty();
    }

    /** Resolves or creates a {@code discovered} placeholder table (telemetry saw it first). */
    public DbTable resolveOrDiscoverTable(DatabaseInstance db, String schema, String name, String defaultSchema) {
        Optional<DbTable> found = findTable(db.getId(), schema, name, defaultSchema, db.getCollector().getSchemas());
        if (found.isPresent()) return found.get();
        String n = unquote(name);
        String s = schema;
        if (n.contains(".") && s == null) { String[] p = n.split("\\.", 2); s = p[0]; n = p[1]; }
        if (s == null) s = defaultSchema != null ? defaultSchema : db.getCollector().getSchemas().size() == 1 ? db.getCollector().getSchemas().get(0) : defaultSchemaOf(db);
        DbTable t = new DbTable();
        t.setId(Ids.newId());
        t.setDatabaseId(db.getId());
        t.setSchema(normalizeIdent(db, unquote(s)));
        t.setName(normalizeIdent(db, n));
        t.setKind(Enums.TableKind.TABLE);
        t.setDiscovered(true);
        t.setFirstSeenAt(Instant.now());
        t.setLastSeenAt(Instant.now());
        applySchemaOwnership(t);
        return tables.save(t);
    }

    public static String defaultSchemaOf(DatabaseInstance db) {
        return switch (db.getEngine()) {
            case ORACLE -> "UNKNOWN";
            case POSTGRES -> "public";
            case MSSQL -> "dbo";
        };
    }

    /** Oracle and SQL Server-ish identifiers are stored upper case unless quoted; PostgreSQL lower case. */
    public static String normalizeIdent(DatabaseInstance db, String ident) {
        if (ident == null) return null;
        if (ident.startsWith("\"")) return unquote(ident);
        return switch (db.getEngine()) {
            case ORACLE -> ident.toUpperCase();
            case POSTGRES -> ident.toLowerCase();
            case MSSQL -> ident;
        };
    }

    public static String unquote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("`") && t.endsWith("`")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    // ---- routines ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Routine> searchRoutines(String databaseId, String schema, Enums.RoutineKind kind, String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        return routines.search(blankToNull(databaseId), blankToNull(schema), kind, like);
    }

    @Transactional(readOnly = true)
    public Routine getRoutine(String id) { return routines.findById(id).orElseThrow(() -> new ApiException.NotFound("Routine", id)); }

    public Routine updateRoutine(String id, RoutineUpdateRequest in) {
        Routine r = getRoutine(id);
        if (in.ownerTeamId() != null) {
            if (in.ownerTeamId().isBlank()) r.setOwnerTeamId(null);
            else {
                teams.findById(in.ownerTeamId()).orElseThrow(() -> new ApiException.BadRequest("Unknown ownerTeamId '" + in.ownerTeamId() + "'"));
                r.setOwnerTeamId(in.ownerTeamId());
            }
        }
        if (in.description() != null) r.setDescription(in.description());
        if (in.tags() != null) r.setTags(in.tags());
        return routines.save(r);
    }

    public Routine setRoutineOwnership(String id, String teamId) {
        Routine r = getRoutine(id);
        if (teamId == null || teamId.isBlank()) r.setOwnerTeamId(null);
        else {
            teams.findById(teamId).orElseThrow(() -> new ApiException.BadRequest("Unknown teamId '" + teamId + "'"));
            r.setOwnerTeamId(teamId);
        }
        return routines.save(r);
    }

    @Transactional(readOnly = true)
    public Optional<Routine> findRoutine(String databaseId, String schema, String name, String defaultSchema) {
        if (name == null) return Optional.empty();
        String n = unquote(name);
        if (schema != null) return routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(databaseId, unquote(schema), n);
        // "SCHEMA.PKG.PROC" or "PKG.PROC" or "PROC"
        List<Routine> byName = routines.findByDatabaseIdAndNameIgnoreCase(databaseId, n);
        if (byName.size() == 1) return Optional.of(byName.get(0));
        if (byName.isEmpty() && n.contains(".")) {
            String[] parts = n.split("\\.", 2);
            Optional<Routine> r = routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(databaseId, parts[0], parts[1]);
            if (r.isPresent()) return r;
        }
        if (defaultSchema != null) {
            Optional<Routine> r = routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(databaseId, defaultSchema, n);
            if (r.isPresent()) return r;
        }
        return byName.stream().filter(r -> defaultSchema != null && r.getSchema().equalsIgnoreCase(defaultSchema)).findFirst();
    }

    public Routine resolveOrDiscoverRoutine(DatabaseInstance db, String schema, String name, String defaultSchema) {
        Optional<Routine> found = findRoutine(db.getId(), schema, name, defaultSchema);
        if (found.isPresent()) return found.get();
        String n = unquote(name);
        String s = schema;
        if (s == null && n.chars().filter(ch -> ch == '.').count() >= 2) { String[] p = n.split("\\.", 2); s = p[0]; n = p[1]; }
        if (s == null) s = defaultSchema != null ? defaultSchema : defaultSchemaOf(db);
        Routine r = new Routine();
        r.setId(Ids.newId());
        r.setDatabaseId(db.getId());
        r.setSchema(normalizeIdent(db, unquote(s)));
        r.setName(normalizeIdent(db, n));
        r.setKind(Enums.RoutineKind.PROCEDURE);
        r.setDiscovered(true);
        r.setFirstSeenAt(Instant.now());
        r.setLastSeenAt(Instant.now());
        return routines.save(r);
    }

    // ---- dependencies ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Dependency> searchDependencies(String fromId, String toId, DependencyKind kind) {
        return dependencies.search(blankToNull(fromId), blankToNull(toId), kind);
    }

    public Dependency declareDependency(Dependency in) {
        checkObject(in.getFromType(), in.getFromId());
        checkObject(in.getToType(), in.getToId());
        Enums.DependencySource src = Enums.DependencySource.DECLARED;
        Optional<Dependency> existing = dependencies.findByFromTypeAndFromIdAndToTypeAndToIdAndKindAndSource(in.getFromType(), in.getFromId(), in.getToType(), in.getToId(), in.getKind(), src);
        if (existing.isPresent()) return existing.get();
        Dependency d = new Dependency();
        d.setId(Ids.newId());
        d.setFromType(in.getFromType()); d.setFromId(in.getFromId());
        d.setToType(in.getToType()); d.setToId(in.getToId());
        d.setKind(in.getKind()); d.setSource(src); d.setConfidence(1.0);
        d.setFirstSeenAt(Instant.now()); d.setLastSeenAt(Instant.now());
        return dependencies.save(d);
    }

    public void deleteDependency(String id) {
        Dependency d = dependencies.findById(id).orElseThrow(() -> new ApiException.NotFound("Dependency", id));
        if (d.getSource() != Enums.DependencySource.DECLARED) throw new ApiException.Conflict("Only DECLARED dependencies can be deleted (this one is " + d.getSource() + ")");
        dependencies.delete(d);
    }

    /** Upsert for crawlers: refreshes lastSeenAt / confidence. */
    public Dependency upsertDependency(ObjectType fromType, String fromId, ObjectType toType, String toId, DependencyKind kind, Enums.DependencySource source, double confidence) {
        Dependency d = dependencies.findByFromTypeAndFromIdAndToTypeAndToIdAndKindAndSource(fromType, fromId, toType, toId, kind, source).orElseGet(() -> {
            Dependency n = new Dependency();
            n.setId(Ids.newId());
            n.setFromType(fromType); n.setFromId(fromId); n.setToType(toType); n.setToId(toId);
            n.setKind(kind); n.setSource(source); n.setFirstSeenAt(Instant.now());
            return n;
        });
        d.setConfidence(confidence);
        d.setLastSeenAt(Instant.now());
        return dependencies.save(d);
    }

    /**
     * Tables reachable from a routine through dictionary/declared dependencies (REFERENCES/READS/WRITES/
     * CALLS/TRIGGERS), transitively. Result maps tableId → access (WRITES if any path writes, else READS).
     */
    @Transactional(readOnly = true)
    public Map<String, Enums.RelationshipKind> expandRoutineToTables(String routineId) {
        Map<String, Enums.RelationshipKind> out = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(routineId);
        visited.add(routineId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Dependency d : dependencies.findByFromId(cur)) {
                if (d.getToType() == ObjectType.TABLE) {
                    Enums.RelationshipKind k = switch (d.getKind()) {
                        case WRITES -> Enums.RelationshipKind.WRITES;
                        default -> Enums.RelationshipKind.READS;
                    };
                    out.merge(d.getToId(), k, (a, b) -> a == Enums.RelationshipKind.WRITES || b == Enums.RelationshipKind.WRITES ? Enums.RelationshipKind.WRITES : Enums.RelationshipKind.READS);
                    // a table written by a routine may fire triggers: follow TRIGGERS edges of that table;
                    // a view (Table.kind = VIEW) reads its base tables: follow its REFERENCES/READS edges
                    for (Dependency tr : dependencies.findByFromId(d.getToId())) {
                        if (tr.getKind() == DependencyKind.TRIGGERS && visited.add(tr.getToId())) queue.add(tr.getToId());
                        else if (tr.getToType() == ObjectType.TABLE && (tr.getKind() == DependencyKind.REFERENCES || tr.getKind() == DependencyKind.READS)) {
                            out.putIfAbsent(tr.getToId(), Enums.RelationshipKind.READS);
                        }
                    }
                } else if (visited.add(d.getToId())) {
                    queue.add(d.getToId());
                }
            }
        }
        return out;
    }

    // ---- relationships ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Relationship> searchRelationships(String applicationId, String objectId, Enums.RelationshipKind kind, Enums.RelationshipSource source) {
        return relationships.search(blankToNull(applicationId), blankToNull(objectId), kind, source);
    }

    public Relationship declareRelationship(Relationship in) {
        applications.findById(in.getApplicationId()).orElseThrow(() -> new ApiException.BadRequest("Unknown applicationId '" + in.getApplicationId() + "'"));
        checkObject(in.getObjectType(), in.getObjectId());
        Relationship r = relationships.findExisting(in.getApplicationId(), in.getObjectType(), in.getObjectId(), in.getKind(), Enums.RelationshipSource.DECLARED, null)
                .orElseGet(() -> {
                    Relationship n = new Relationship();
                    n.setId(Ids.newId());
                    n.setApplicationId(in.getApplicationId()); n.setObjectType(in.getObjectType()); n.setObjectId(in.getObjectId());
                    n.setKind(in.getKind()); n.setSource(Enums.RelationshipSource.DECLARED);
                    n.setFirstSeenAt(Instant.now()); n.setLastSeenAt(Instant.now());
                    return n;
                });
        r.setConfirmed(true);
        r.setConfidence(1.0);
        return relationships.save(r);
    }

    public Relationship setRelationshipConfirmed(String id, boolean confirmed) {
        Relationship r = relationships.findById(id).orElseThrow(() -> new ApiException.NotFound("Relationship", id));
        r.setConfirmed(confirmed);
        return relationships.save(r);
    }

    public void deleteRelationship(String id) {
        relationships.delete(relationships.findById(id).orElseThrow(() -> new ApiException.NotFound("Relationship", id)));
    }

    /** Upsert used by telemetry/collectors: increments queryCount and refreshes lastSeenAt. */
    public Relationship recordRelationship(String applicationId, ObjectType objectType, String objectId, Enums.RelationshipKind kind,
                                           Enums.RelationshipSource source, String viaRoutineId, long increment, Instant seenAt) {
        Relationship r = relationships.findExisting(applicationId, objectType, objectId, kind, source, viaRoutineId).orElseGet(() -> {
            Relationship n = new Relationship();
            n.setId(Ids.newId());
            n.setApplicationId(applicationId); n.setObjectType(objectType); n.setObjectId(objectId);
            n.setKind(kind); n.setSource(source); n.setViaRoutineId(viaRoutineId);
            n.setFirstSeenAt(seenAt);
            n.setConfidence(Enums.confidenceOf(source));
            return n;
        });
        r.setQueryCount(r.getQueryCount() + increment);
        if (r.getLastSeenAt() == null || seenAt.isAfter(r.getLastSeenAt())) r.setLastSeenAt(seenAt);
        return relationships.save(r);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void checkObject(ObjectType type, String id) {
        boolean ok = type == ObjectType.TABLE ? tables.existsById(id) : routines.existsById(id);
        if (!ok) throw new ApiException.BadRequest("Unknown " + type + " id '" + id + "'");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    public List<DbTable> tablesByIds(Set<String> ids) { return ids.isEmpty() ? List.of() : tables.findByIdIn(ids); }
    public List<Routine> routinesByIds(Set<String> ids) { return ids.isEmpty() ? new ArrayList<>() : routines.findByIdIn(ids); }
}
