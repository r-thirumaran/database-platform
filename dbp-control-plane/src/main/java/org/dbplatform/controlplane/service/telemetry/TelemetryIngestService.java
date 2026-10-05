package org.dbplatform.controlplane.service.telemetry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dbplatform.common.telemetry.AccessType;
import org.dbplatform.common.telemetry.ConnectionEvent;
import org.dbplatform.common.telemetry.ConnectionEventType;
import org.dbplatform.common.telemetry.PoolStats;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.ConnectionEventRaw;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.PoolStatsSnapshot;
import org.dbplatform.controlplane.domain.QueryEventRaw;
import org.dbplatform.controlplane.domain.QueryStat;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.ConnectionEventRawRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.PoolStatsSnapshotRepository;
import org.dbplatform.controlplane.repo.QueryEventRawRepository;
import org.dbplatform.controlplane.repo.QueryStatRepository;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telemetry sink (docs/control-plane-api.md §9, docs/telemetry-events.md "Derivation rules").
 * Idempotent on {@code eventId}: resolves ids, creates discovered tables, upserts relationships,
 * aggregates hourly query statistics and stores raw events for the retention window.
 */
@Service
@Transactional
public class TelemetryIngestService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);
    private static final int RESERVOIR = 100;

    private final QueryEventRawRepository rawQueries;
    private final ConnectionEventRawRepository rawConnections;
    private final PoolStatsSnapshotRepository pools;
    private final QueryStatRepository stats;
    private final ApplicationRepository applications;
    private final DatasourceRepository datasources;
    private final DatabaseRepository databases;
    private final DatasourceService datasourceService;
    private final CatalogueService catalogue;
    private final LiveConnectionRegistry live;

    public TelemetryIngestService(QueryEventRawRepository rawQueries, ConnectionEventRawRepository rawConnections,
                                  PoolStatsSnapshotRepository pools, QueryStatRepository stats, ApplicationRepository applications,
                                  DatasourceRepository datasources, DatabaseRepository databases, DatasourceService datasourceService,
                                  CatalogueService catalogue, LiveConnectionRegistry live) {
        this.rawQueries = rawQueries; this.rawConnections = rawConnections; this.pools = pools; this.stats = stats;
        this.applications = applications; this.datasources = datasources; this.databases = databases;
        this.datasourceService = datasourceService; this.catalogue = catalogue; this.live = live;
    }

    // ---- queries ---------------------------------------------------------------------------------

    public int ingestQueries(List<QueryEvent> events) {
        int accepted = 0;
        Map<String, Application> appCache = new HashMap<>();
        Map<String, DatabaseInstance> dbCache = new HashMap<>();
        for (QueryEvent e : events) {
            if (e == null) continue;
            try {
                if (ingestQuery(e, appCache, dbCache)) accepted++;
            } catch (RuntimeException ex) {
                log.warn("Failed to ingest query event {}: {}", e.eventId(), ex.toString());
            }
        }
        return accepted;
    }

    private boolean ingestQuery(QueryEvent e, Map<String, Application> appCache, Map<String, DatabaseInstance> dbCache) {
        String eventId = e.eventId() != null && !e.eventId().isBlank() ? e.eventId() : Ids.newId();
        if (rawQueries.existsById(eventId)) return false;
        Instant ts = e.timestamp() != null ? e.timestamp() : Instant.now();

        Application app = resolveApplication(e.applicationId(), e.application(), appCache);
        DatabaseInstance db = resolveDatabase(e.databaseId(), e.datasource(), app, dbCache);

        QueryEventRaw raw = toRaw(e, eventId, ts, app, db);
        rawQueries.save(raw);

        List<Map<String, String>> tableRefs = new ArrayList<>();
        if (db != null) {
            for (TableAccess ta : e.tables()) {
                DbTable t = catalogue.resolveOrDiscoverTable(db, ta.schema(), ta.name(), e.defaultSchema());
                touch(t, ts);
                RelationshipKind kind = ta.access() == AccessType.WRITE ? RelationshipKind.WRITES : RelationshipKind.READS;
                tableRefs.add(Map.of("tableId", t.getId(), "access", kind == RelationshipKind.WRITES ? "WRITE" : "READ"));
                if (app != null) catalogue.recordRelationship(app.getId(), ObjectType.TABLE, t.getId(), kind, RelationshipSource.GATEWAY, null, 1, ts);
            }
            for (RoutineRef rr : e.routines()) {
                Routine r = catalogue.resolveOrDiscoverRoutine(db, rr.schema(), rr.name(), e.defaultSchema());
                r.setLastSeenAt(ts);
                if (app != null) {
                    catalogue.recordRelationship(app.getId(), ObjectType.ROUTINE, r.getId(), RelationshipKind.CALLS, RelationshipSource.GATEWAY, null, 1, ts);
                    // CALL expansion: A READS/WRITES T for every T in R's transitive dictionary dependencies, viaRoutineId = R
                    for (Map.Entry<String, RelationshipKind> ex : catalogue.expandRoutineToTables(r.getId()).entrySet()) {
                        catalogue.recordRelationship(app.getId(), ObjectType.TABLE, ex.getKey(), ex.getValue(), RelationshipSource.GATEWAY, r.getId(), 1, ts);
                        tableRefs.add(Map.of("tableId", ex.getKey(), "access", ex.getValue() == RelationshipKind.WRITES ? "WRITE" : "READ", "via", r.getId()));
                    }
                }
            }
        }
        aggregate(e, ts, app, db, tableRefs);
        return true;
    }

    private void touch(DbTable t, Instant ts) {
        if (t.getFirstSeenAt() == null) t.setFirstSeenAt(ts);
        if (t.getLastSeenAt() == null || ts.isAfter(t.getLastSeenAt())) t.setLastSeenAt(ts);
    }

    private void aggregate(QueryEvent e, Instant ts, Application app, DatabaseInstance db, List<Map<String, String>> tableRefs) {
        String hash = e.sqlHash() != null && !e.sqlHash().isBlank() ? e.sqlHash() : org.dbplatform.controlplane.service.SecretCipher.sha256Hex(e.sqlNormalized() == null ? "" : e.sqlNormalized());
        Instant bucket = ts.truncatedTo(ChronoUnit.HOURS);
        String appId = app == null ? null : app.getId();
        String dbId = db == null ? null : db.getId();
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
        s.setExecCount(s.getExecCount() + 1);
        long dur = Math.max(0, e.durationMs());
        s.setTotalDurationMs(s.getTotalDurationMs() + dur);
        s.setMaxDurationMs(Math.max(s.getMaxDurationMs(), dur));
        if (e.rows() > 0) s.setRowCount(s.getRowCount() + e.rows());
        if (!e.success()) s.setErrorCount(s.getErrorCount() + 1);
        if (s.getLastSeenAt() == null || ts.isAfter(s.getLastSeenAt())) s.setLastSeenAt(ts);
        List<Long> samples = Optional.ofNullable(Json.read(s.getDurationSamples(), new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {})).orElseGet(ArrayList::new);
        if (samples.size() < RESERVOIR) samples.add(dur);
        else samples.set((int) (s.getExecCount() % RESERVOIR), dur);
        s.setDurationSamples(Json.write(samples));
        if (!tableRefs.isEmpty()) {
            List<Map<String, String>> merged = Optional.ofNullable(Json.read(s.getTablesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {})).orElseGet(ArrayList::new);
            for (Map<String, String> ref : tableRefs) {
                if (merged.stream().noneMatch(m -> m.get("tableId").equals(ref.get("tableId")) && m.get("access").equals(ref.get("access")))) merged.add(ref);
            }
            s.setTablesJson(Json.write(merged));
        }
        stats.save(s);
    }

    private QueryEventRaw toRaw(QueryEvent e, String eventId, Instant ts, Application app, DatabaseInstance db) {
        QueryEventRaw r = new QueryEventRaw();
        r.setEventId(eventId);
        r.setReceivedAt(Instant.now());
        r.setEventTime(ts);
        r.setGatewayId(e.gatewayId());
        r.setSessionId(e.sessionId());
        r.setApplicationId(app != null ? app.getId() : e.applicationId());
        r.setApplicationName(app != null ? app.getName() : e.application());
        r.setTeamName(e.team());
        r.setDatasourceName(e.datasource());
        r.setDatabaseId(db != null ? db.getId() : e.databaseId());
        r.setEngine(e.engine() == null ? null : e.engine().name());
        r.setSqlHash(e.sqlHash());
        r.setSqlNormalized(e.sqlNormalized());
        r.setOperation(e.operation() == null ? null : e.operation().name());
        r.setTablesJson(TelemetryJson.toJson(e.tables()));
        r.setRoutinesJson(TelemetryJson.toJson(e.routines()));
        r.setColumnsJson(TelemetryJson.toJson(e.columns()));
        r.setDurationMs(e.durationMs());
        r.setRowCount(e.rows());
        r.setSuccess(e.success());
        r.setSqlState(e.sqlState());
        r.setErrorCode(e.errorCode());
        r.setErrorMessage(e.errorMessage());
        r.setPinned(e.pinned());
        r.setPoolMode(e.poolMode());
        r.setClientInfo(e.clientInfo() == null ? null : Json.write(e.clientInfo()));
        r.setDefaultSchema(e.defaultSchema());
        return r;
    }

    private Application resolveApplication(String id, String name, Map<String, Application> cache) {
        String key = id != null ? "id:" + id : name != null ? "name:" + name : null;
        if (key == null) return null;
        return cache.computeIfAbsent(key, k -> {
            Optional<Application> a = id != null ? applications.findById(id) : Optional.empty();
            if (a.isEmpty() && name != null) a = applications.findByName(name);
            return a.orElse(null);
        });
    }

    private DatabaseInstance resolveDatabase(String databaseId, String datasourceName, Application app, Map<String, DatabaseInstance> cache) {
        String key = databaseId != null ? "id:" + databaseId : datasourceName != null ? "ds:" + datasourceName + ":" + (app == null ? "-" : app.getId()) : null;
        if (key == null) return null;
        return cache.computeIfAbsent(key, k -> {
            if (databaseId != null) {
                Optional<DatabaseInstance> d = databases.findById(databaseId);
                if (d.isPresent()) return d.get();
            }
            if (datasourceName != null) {
                Optional<Datasource> ds = datasources.findByName(datasourceName);
                if (ds.isPresent()) return datasourceService.resolve(ds.get(), app, null).database();
            }
            return null;
        });
    }

    // ---- connections -----------------------------------------------------------------------------

    public int ingestConnections(List<ConnectionEvent> events) {
        int accepted = 0;
        for (ConnectionEvent e : events) {
            if (e == null) continue;
            try {
                String eventId = e.eventId() != null && !e.eventId().isBlank() ? e.eventId() : Ids.newId();
                if (rawConnections.existsById(eventId)) continue;
                rawConnections.save(toRaw(e, eventId));
                if (e.eventType() == ConnectionEventType.OPEN) {
                    live.opened(ComponentService.toProxyConnection(e.proxyId(), e));
                } else if (e.eventType() == ConnectionEventType.CLOSE || e.eventType() == ConnectionEventType.BACKEND_FAILED) {
                    live.closed(e.backendHost(), e.backendPort(), e.proxyLocalPort());
                }
                accepted++;
            } catch (RuntimeException ex) {
                log.warn("Failed to ingest connection event {}: {}", e.eventId(), ex.toString());
            }
        }
        return accepted;
    }

    private ConnectionEventRaw toRaw(ConnectionEvent e, String eventId) {
        ConnectionEventRaw r = new ConnectionEventRaw();
        r.setEventId(eventId);
        r.setReceivedAt(Instant.now());
        r.setEventTime(e.timestamp());
        r.setProxyId(e.proxyId());
        r.setEventType(e.eventType() == null ? null : e.eventType().name());
        r.setListener(e.listener());
        r.setEngine(e.engine() == null ? null : e.engine().name());
        r.setConnectionId(e.connectionId());
        r.setClientAddr(e.clientAddr());
        r.setClientPort(e.clientPort());
        r.setProxyLocalAddr(e.proxyLocalAddr());
        r.setProxyLocalPort(e.proxyLocalPort());
        r.setBackendHost(e.backendHost());
        r.setBackendPort(e.backendPort());
        r.setRequestedService(e.requestedService());
        r.setResolvedService(e.resolvedService());
        r.setApplicationId(e.applicationId());
        r.setApplicationName(e.application());
        r.setIdentitySource(e.identitySource() == null ? null : e.identitySource().name());
        r.setDatasourceId(e.datasourceId());
        r.setDatasourceName(e.datasource());
        r.setProgram(e.program());
        r.setClientHost(e.clientHost());
        r.setOsUser(e.osUser());
        r.setDbUser(e.dbUser());
        r.setOpenedAt(e.openedAt());
        r.setClosedAt(e.closedAt());
        r.setDurationMs(e.durationMs());
        r.setBytesIn(e.bytesIn());
        r.setBytesOut(e.bytesOut());
        r.setReason(e.reason());
        return r;
    }

    // ---- pools -----------------------------------------------------------------------------------

    public int ingestPools(List<PoolStats> list) {
        int accepted = 0;
        for (PoolStats p : list) {
            if (p == null) continue;
            PoolStatsSnapshot s = new PoolStatsSnapshot();
            s.setId(Ids.newId());
            s.setRecordedAt(Instant.now());
            s.setEventTime(p.timestamp());
            s.setGatewayId(p.gatewayId());
            s.setDatasourceName(p.datasource());
            String dsId = p.datasourceId();
            if (dsId == null && p.datasource() != null) dsId = datasources.findByName(p.datasource()).map(Datasource::getId).orElse(null);
            s.setDatasourceId(dsId);
            s.setDatabaseId(p.databaseId());
            s.setEngine(p.engine() == null ? null : p.engine().name());
            s.setActive(p.active()); s.setIdle(p.idle()); s.setWaiting(p.waiting()); s.setTotal(p.total()); s.setMax(p.max());
            s.setLogicalSessions(p.logicalSessions()); s.setPinnedSessions(p.pinnedSessions());
            s.setCredentialVersion((long) p.credentialVersion());
            pools.save(s);
            accepted++;
        }
        return accepted;
    }

    /** Latest snapshot per (gateway, datasource). */
    @Transactional(readOnly = true)
    public List<PoolStatsSnapshot> latestPools() {
        Map<String, PoolStatsSnapshot> latest = new LinkedHashMap<>();
        for (PoolStatsSnapshot p : pools.findByRecordedAtGreaterThanEqualOrderByRecordedAtDesc(Instant.now().minusSeconds(3600))) {
            latest.putIfAbsent(p.getGatewayId() + "|" + p.getDatasourceName(), p);
        }
        return new ArrayList<>(latest.values());
    }

    public static Enums.Engine toEngine(org.dbplatform.common.telemetry.Engine e) {
        if (e == null) return null;
        return switch (e) {
            case ORACLE -> Enums.Engine.ORACLE;
            case POSTGRES -> Enums.Engine.POSTGRES;
            case MSSQL -> Enums.Engine.MSSQL;
            default -> null;
        };
    }
}
