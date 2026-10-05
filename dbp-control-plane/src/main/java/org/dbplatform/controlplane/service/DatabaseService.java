package org.dbplatform.controlplane.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.collector.CollectorConnections;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.CredentialRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.DependencyRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.SchemaOwnershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DatabaseService {
    private final DatabaseRepository databases;
    private final CredentialRepository credentials;
    private final DatasourceRepository datasources;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final DependencyRepository dependencies;
    private final RelationshipRepository relationships;
    private final ConfigVersionService configVersion;
    private final CollectorConnections connections;
    private final SchemaOwnershipRepository schemaOwnership;

    public DatabaseService(DatabaseRepository databases, CredentialRepository credentials, DatasourceRepository datasources,
                           DbTableRepository tables, RoutineRepository routines, DependencyRepository dependencies,
                           RelationshipRepository relationships, ConfigVersionService configVersion, CollectorConnections connections,
                           SchemaOwnershipRepository schemaOwnership) {
        this.databases = databases; this.credentials = credentials; this.datasources = datasources; this.tables = tables;
        this.routines = routines; this.dependencies = dependencies; this.relationships = relationships;
        this.configVersion = configVersion; this.connections = connections; this.schemaOwnership = schemaOwnership;
    }

    @Transactional(readOnly = true)
    public List<DatabaseInstance> list() { return databases.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public DatabaseInstance get(String id) { return databases.findById(id).orElseThrow(() -> new ApiException.NotFound("Database", id)); }

    public DatabaseInstance create(DatabaseInstance in) {
        databases.findByName(in.getName()).ifPresent(d -> { throw new ApiException.Conflict("Database '" + in.getName() + "' already exists"); });
        DatabaseInstance d = new DatabaseInstance();
        d.setId(Ids.newId());
        d.setCreatedAt(Instant.now());
        copy(in, d);
        configVersion.bump();
        return databases.save(d);
    }

    public DatabaseInstance update(String id, DatabaseInstance in) {
        DatabaseInstance d = get(id);
        databases.findByName(in.getName()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ApiException.Conflict("Database '" + in.getName() + "' already exists"); });
        copy(in, d);
        configVersion.bump();
        return databases.save(d);
    }

    public DatabaseInstance upsertByName(DatabaseInstance in) {
        return databases.findByName(in.getName()).map(e -> update(e.getId(), in)).orElseGet(() -> create(in));
    }

    public void delete(String id) {
        DatabaseInstance d = get(id);
        if (!datasources.findByCurrentDatabaseIdOrTargetDatabaseId(id, id).isEmpty()) {
            throw new ApiException.Conflict("Database '" + d.getName() + "' is referenced by a datasource");
        }
        List<String> tableIds = tables.findByDatabaseId(id).stream().map(DbTable::getId).toList();
        List<String> routineIds = routines.findByDatabaseId(id).stream().map(Routine::getId).toList();
        List<String> all = new ArrayList<>(tableIds);
        all.addAll(routineIds);
        if (!all.isEmpty()) {
            dependencies.deleteByFromIdInOrToIdIn(all, all);
            relationships.deleteByObjectIdIn(all);
        }
        routines.deleteByDatabaseId(id);
        tables.deleteByDatabaseId(id);
        schemaOwnership.deleteByDatabaseId(id);
        databases.delete(d);
        configVersion.bump();
    }

    public record TestResult(boolean ok, String productName, String productVersion, long latencyMs, String error) {}

    /** Opens one JDBC connection with the database's platform credential. */
    public TestResult testConnection(String id) {
        DatabaseInstance d = get(id);
        long start = System.nanoTime();
        try (Connection c = connections.open(d)) {
            DatabaseMetaData md = c.getMetaData();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new TestResult(true, md.getDatabaseProductName(), md.getDatabaseProductVersion(), ms, null);
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new TestResult(false, null, null, ms, e.getMessage());
        }
    }

    public record SchemaInfo(String name, long tableCount, long routineCount) {}

    @Transactional(readOnly = true)
    public List<SchemaInfo> schemas(String id) {
        get(id);
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (DbTable t : tables.findByDatabaseIdOrderBySchemaAscNameAsc(id)) acc.computeIfAbsent(t.getSchema(), k -> new long[2])[0]++;
        for (Routine r : routines.findByDatabaseId(id)) acc.computeIfAbsent(r.getSchema(), k -> new long[2])[1]++;
        List<SchemaInfo> out = new ArrayList<>();
        acc.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.add(new SchemaInfo(e.getKey(), e.getValue()[0], e.getValue()[1])));
        return out;
    }

    private void copy(DatabaseInstance in, DatabaseInstance d) {
        if (in.getCredentialId() != null && credentials.findById(in.getCredentialId()).isEmpty()) {
            throw new ApiException.BadRequest("Unknown credentialId '" + in.getCredentialId() + "'");
        }
        if (in.getCollector().getCredentialId() != null && credentials.findById(in.getCollector().getCredentialId()).isEmpty()) {
            throw new ApiException.BadRequest("Unknown collector.credentialId '" + in.getCollector().getCredentialId() + "'");
        }
        d.setName(in.getName());
        d.setEngine(in.getEngine());
        d.setHost(in.getHost());
        d.setPort(in.getPort());
        d.setServiceName(in.getServiceName());
        d.setCredentialId(in.getCredentialId());
        d.setMaxPhysicalConnections(in.getMaxPhysicalConnections());
        d.setJdbcProperties(in.getJdbcProperties());
        d.setCollector(in.getCollector());
        d.setDescription(in.getDescription());
        d.setTags(in.getTags());
        d.setUpdatedAt(Instant.now());
    }
}
