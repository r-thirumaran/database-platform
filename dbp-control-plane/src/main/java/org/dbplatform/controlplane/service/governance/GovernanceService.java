package org.dbplatform.controlplane.service.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.PolicyKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.Enums.Severity;
import org.dbplatform.controlplane.domain.Enums.ViolationStatus;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Policy;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.domain.Violation;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.PolicyRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.ViolationRepository;
import org.dbplatform.controlplane.service.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Governance: the five policies of docs/control-plane-api.md §11 evaluated against relationships,
 * ownership, producers and grants. Also derives the INFERRED producer (sole writer).
 */
@Service
public class GovernanceService {
    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    private final PolicyRepository policies;
    private final ViolationRepository violations;
    private final RelationshipRepository relationships;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final ApplicationRepository applications;
    private final AccessGrantRepository grants;
    private final DatasourceRepository datasources;
    private final DbpProperties props;
    /** Read phase of an evaluation: read-only (no flush, no dirty checking of the entities it loads). */
    private final TransactionTemplate readTx;
    /** Write phase of an evaluation: short, only the rows that really change. */
    private final TransactionTemplate writeTx;

    public GovernanceService(PolicyRepository policies, ViolationRepository violations, RelationshipRepository relationships, DbTableRepository tables,
                             RoutineRepository routines, ApplicationRepository applications, AccessGrantRepository grants, DatasourceRepository datasources, DbpProperties props,
                             PlatformTransactionManager txManager) {
        this.policies = policies; this.violations = violations; this.relationships = relationships; this.tables = tables; this.routines = routines;
        this.applications = applications; this.grants = grants; this.datasources = datasources; this.props = props;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureDefaultPolicies() {
        Map<PolicyKind, Object[]> defaults = new LinkedHashMap<>();
        defaults.put(PolicyKind.CROSS_TEAM_DIRECT_ACCESS, new Object[]{Severity.MEDIUM, "An application accesses a table owned by another team without a declared/confirmed relationship"});
        defaults.put(PolicyKind.UNOWNED_TABLE, new Object[]{Severity.LOW, "A table that is accessed at runtime has no owner team"});
        defaults.put(PolicyKind.UNDECLARED_CONSUMER, new Object[]{Severity.MEDIUM, "An application uses a database object without an access grant on a datasource routed to that database"});
        defaults.put(PolicyKind.WRITE_BY_NON_PRODUCER, new Object[]{Severity.HIGH, "A table is written by an application that is not its declared producer"});
        defaults.put(PolicyKind.DIRECT_DB_ACCESS_BYPASSING_PLATFORM, new Object[]{Severity.MEDIUM, "A collector observed an application connecting to the database directly (not through gateway or proxy)"});
        for (Map.Entry<PolicyKind, Object[]> e : defaults.entrySet()) {
            if (policies.findByKind(e.getKey()).isEmpty()) {
                Policy p = new Policy();
                p.setId(Ids.newId());
                p.setKind(e.getKey());
                p.setEnabled(true);
                p.setSeverity((Severity) e.getValue()[0]);
                p.setDescription((String) e.getValue()[1]);
                policies.save(p);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Policy> listPolicies() { return policies.findAllByOrderByKindAsc(); }

    @Transactional
    public Policy updatePolicy(String id, Policy in) {
        Policy p = policies.findById(id).orElseThrow(() -> new ApiException.NotFound("Policy", id));
        p.setEnabled(in.isEnabled());
        if (in.getSeverity() != null) p.setSeverity(in.getSeverity());
        if (in.getDescription() != null) p.setDescription(in.getDescription());
        return policies.save(p);
    }

    @Transactional(readOnly = true)
    public List<Violation> listViolations(ViolationStatus status) {
        return status == null ? violations.findAllByOrderByLastSeenAtDesc() : violations.findByStatusOrderBySeverityDescLastSeenAtDesc(status);
    }

    @Transactional
    public Violation updateViolation(String id, ViolationStatus status) {
        Violation v = violations.findById(id).orElseThrow(() -> new ApiException.NotFound("Violation", id));
        if (status != null) v.setStatus(status);
        return violations.save(v);
    }

    @Scheduled(initialDelayString = "${dbp.governance.interval-seconds:300}000", fixedDelayString = "${dbp.governance.interval-seconds:300}000")
    public void scheduled() {
        if (!props.getGovernance().isEnabled()) return;
        try {
            evaluate();
        } catch (RuntimeException e) {
            log.warn("Governance evaluation failed: {}", e.toString());
        }
    }

    public record EvaluationResult(int open, int resolved, int inferredProducers) {}

    /**
     * Recomputes all violations; new ones are OPEN, vanished ones become RESOLVED, ACKNOWLEDGED is preserved.
     * <p>Three short phases instead of one long read-write transaction: (1) default policies (writes only when one is missing),
     * (2) the whole computation in a <em>read-only</em> transaction (nothing it loads is flushed or dirty-checked, so applications,
     * tables and datasources are never rewritten and never row-locked by an evaluation), (3) one short write transaction that applies the
     * result (violations, inferred producers re-checked against the freshly loaded table).
     */
    public EvaluationResult evaluate() {
        writeTx.executeWithoutResult(s -> ensureDefaultPolicies());
        Computed computed = readTx.execute(s -> compute());
        return writeTx.execute(s -> apply(computed));
    }

    /** Result of the read phase: the violations found, and the producers to infer (ids only, nothing managed leaves the read transaction). */
    private record Computed(Map<String, Violation> found, List<ProducerInference> inferences, Instant now) {}

    private record ProducerInference(String tableId, String applicationId, String applicationTeamId) {}

    private Computed compute() {
        Map<PolicyKind, Policy> pol = new HashMap<>();
        policies.findAll().forEach(p -> pol.put(p.getKind(), p));
        Map<String, Application> apps = new HashMap<>();
        applications.findAll().forEach(a -> apps.put(a.getId(), a));
        Map<String, DbTable> tbls = new HashMap<>();
        tables.findAll().forEach(t -> tbls.put(t.getId(), t));
        Map<String, Routine> routs = new HashMap<>();
        routines.findAll().forEach(r -> routs.put(r.getId(), r));
        List<Relationship> rels = relationships.findAll();

        // application → database ids it is granted (via datasources current/target database)
        Map<String, Set<String>> grantedDbs = new HashMap<>();
        Map<String, Datasource> dss = new HashMap<>();
        datasources.findAll().forEach(d -> dss.put(d.getId(), d));
        for (AccessGrant g : grants.findAll()) {
            if (!g.isEnabled()) continue;
            Datasource ds = dss.get(g.getDatasourceId());
            if (ds == null) continue;
            Set<String> set = grantedDbs.computeIfAbsent(g.getApplicationId(), k -> new HashSet<>());
            if (ds.getCurrentDatabaseId() != null) set.add(ds.getCurrentDatabaseId());
            if (ds.getTargetDatabaseId() != null) set.add(ds.getTargetDatabaseId());
            ds.getRoutingRules().forEach(r -> set.add(r.getDatabaseId()));
        }
        // declared / confirmed (app, object) pairs and platform-observed pairs
        Set<String> declared = new HashSet<>();
        Set<String> viaPlatform = new HashSet<>();
        Set<String> runtimeTouched = new HashSet<>();
        for (Relationship r : rels) {
            String pair = r.getApplicationId() + "|" + r.getObjectId();
            if (r.getSource() == RelationshipSource.DECLARED || r.isConfirmed()) declared.add(pair);
            if (r.getSource() == RelationshipSource.GATEWAY || r.getSource() == RelationshipSource.PROXY_CORRELATION) viaPlatform.add(pair);
            if (r.getSource() != RelationshipSource.DECLARED) runtimeTouched.add(r.getObjectId());
        }

        Map<String, Violation> found = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (Relationship r : rels) {
            Application app = apps.get(r.getApplicationId());
            if (app == null) continue;
            String pair = r.getApplicationId() + "|" + r.getObjectId();
            DbTable table = r.getObjectType() == ObjectType.TABLE ? tbls.get(r.getObjectId()) : null;
            Routine routine = r.getObjectType() == ObjectType.ROUTINE ? routs.get(r.getObjectId()) : null;
            if (table == null && routine == null) continue;
            String label = table != null ? table.label() : routine.label();
            String ownerTeam = table != null ? table.getOwnerTeamId() : routine.getOwnerTeamId();
            String databaseId = table != null ? table.getDatabaseId() : routine.getDatabaseId();
            boolean runtime = r.getSource() != RelationshipSource.DECLARED;

            if (runtime && enabled(pol, PolicyKind.CROSS_TEAM_DIRECT_ACCESS) && ownerTeam != null && app.getTeamId() != null
                    && !ownerTeam.equals(app.getTeamId()) && !declared.contains(pair)) {
                Severity sev = r.getKind() == RelationshipKind.WRITES ? Severity.HIGH : pol.get(PolicyKind.CROSS_TEAM_DIRECT_ACCESS).getSeverity();
                add(found, PolicyKind.CROSS_TEAM_DIRECT_ACCESS, sev, app, r.getObjectType(), r.getObjectId(), label,
                        app.getName() + " " + r.getKind() + " " + label + " owned by another team without a declared relationship (source " + r.getSource() + ")", now);
            }
            if (runtime && enabled(pol, PolicyKind.UNDECLARED_CONSUMER) && !declared.contains(pair)
                    && !grantedDbs.getOrDefault(app.getId(), Set.of()).contains(databaseId)) {
                add(found, PolicyKind.UNDECLARED_CONSUMER, pol.get(PolicyKind.UNDECLARED_CONSUMER).getSeverity(), app, r.getObjectType(), r.getObjectId(), label,
                        app.getName() + " uses " + label + " but has no access grant on a datasource routed to its database", now);
            }
            if (table != null && r.getKind() == RelationshipKind.WRITES && enabled(pol, PolicyKind.WRITE_BY_NON_PRODUCER)
                    && table.getProducerApplicationId() != null && !table.getProducerApplicationId().equals(app.getId())) {
                Application producer = apps.get(table.getProducerApplicationId());
                add(found, PolicyKind.WRITE_BY_NON_PRODUCER, pol.get(PolicyKind.WRITE_BY_NON_PRODUCER).getSeverity(), app, ObjectType.TABLE, table.getId(), label,
                        app.getName() + " writes " + label + (r.getViaRoutineId() != null ? " (via routine)" : "") + " but the producer is " + (producer == null ? table.getProducerApplicationId() : producer.getName()), now);
            }
            if (enabled(pol, PolicyKind.DIRECT_DB_ACCESS_BYPASSING_PLATFORM)
                    && (r.getSource() == RelationshipSource.COLLECTOR_SESSION || r.getSource() == RelationshipSource.COLLECTOR_AUDIT) && !viaPlatform.contains(pair)) {
                add(found, PolicyKind.DIRECT_DB_ACCESS_BYPASSING_PLATFORM, pol.get(PolicyKind.DIRECT_DB_ACCESS_BYPASSING_PLATFORM).getSeverity(), app, r.getObjectType(), r.getObjectId(), label,
                        app.getName() + " was observed on the database directly (" + r.getSource() + ") and never through the gateway/proxy for " + label, now);
            }
        }
        if (enabled(pol, PolicyKind.UNOWNED_TABLE)) {
            for (DbTable t : tbls.values()) {
                if (t.getOwnerTeamId() == null && runtimeTouched.contains(t.getId())) {
                    add(found, PolicyKind.UNOWNED_TABLE, pol.get(PolicyKind.UNOWNED_TABLE).getSeverity(), null, ObjectType.TABLE, t.getId(), t.label(),
                            t.label() + " is accessed at runtime but has no owner team", now);
                }
            }
        }

        return new Computed(found, inferProducers(rels, tbls, apps), now);
    }

    private EvaluationResult apply(Computed computed) {
        Instant now = computed.now();
        Map<String, Violation> found = computed.found();
        int open = 0, resolved = 0;
        Map<String, Violation> existing = new HashMap<>();
        violations.findAll().forEach(v -> existing.put(v.getFingerprint(), v));
        for (Violation v : found.values()) {
            Violation ex = existing.remove(v.getFingerprint());
            if (ex == null) {
                violations.save(v);
                open++;
            } else {
                ex.setLastSeenAt(now);
                ex.setDetail(v.getDetail());
                ex.setSeverity(v.getSeverity());
                if (ex.getStatus() == ViolationStatus.RESOLVED) { ex.setStatus(ViolationStatus.OPEN); ex.setFirstSeenAt(now); }
                if (ex.getStatus() == ViolationStatus.OPEN) open++;
                violations.save(ex);
            }
        }
        for (Violation stale : existing.values()) {
            if (stale.getStatus() != ViolationStatus.RESOLVED) { stale.setStatus(ViolationStatus.RESOLVED); violations.save(stale); resolved++; }
        }
        int inferred = applyProducers(computed.inferences());
        return new EvaluationResult(open, resolved, inferred);
    }

    /** Table written by exactly one application (direct runtime writes) → INFERRED producer, never overwriting a DECLARED one. Read-only. */
    private List<ProducerInference> inferProducers(List<Relationship> rels, Map<String, DbTable> tbls, Map<String, Application> apps) {
        Map<String, Set<String>> writers = new HashMap<>();
        for (Relationship r : rels) {
            if (r.getObjectType() == ObjectType.TABLE && r.getKind() == RelationshipKind.WRITES && r.getSource() != RelationshipSource.DECLARED) {
                writers.computeIfAbsent(r.getObjectId(), k -> new HashSet<>()).add(r.getApplicationId());
            }
        }
        List<ProducerInference> out = new ArrayList<>();
        for (DbTable t : tbls.values()) {
            if (t.getProducerSource() == Enums.OwnerSource.DECLARED && t.getProducerApplicationId() != null) continue;
            Set<String> w = writers.get(t.getId());
            if (w != null && w.size() == 1) {
                String appId = w.iterator().next();
                if (!appId.equals(t.getProducerApplicationId())) {
                    Application a = apps.get(appId);
                    out.add(new ProducerInference(t.getId(), appId, a == null ? null : a.getTeamId()));
                }
            }
        }
        return out;
    }

    /** Applies the inferences to freshly loaded tables (re-checking the conditions: the table may have been curated since the read phase). */
    private int applyProducers(List<ProducerInference> inferences) {
        int n = 0;
        for (ProducerInference inf : inferences) {
            DbTable t = tables.findById(inf.tableId()).orElse(null);
            if (t == null) continue;
            if (t.getProducerSource() == Enums.OwnerSource.DECLARED && t.getProducerApplicationId() != null) continue;
            if (inf.applicationId().equals(t.getProducerApplicationId())) continue;
            t.setProducerApplicationId(inf.applicationId());
            t.setProducerSource(Enums.OwnerSource.INFERRED);
            if (t.getOwnerTeamId() == null && inf.applicationTeamId() != null) {
                t.setOwnerTeamId(inf.applicationTeamId());
                t.setOwnerSource(Enums.OwnerSource.INFERRED);
                t.setOwnerConfirmed(false);
            }
            tables.save(t);
            n++;
        }
        return n;
    }

    private static boolean enabled(Map<PolicyKind, Policy> pol, PolicyKind k) {
        Policy p = pol.get(k);
        return p != null && p.isEnabled();
    }

    private static void add(Map<String, Violation> found, PolicyKind kind, Severity severity, Application app, ObjectType objectType, String objectId,
                            String label, String detail, Instant now) {
        String fp = SecretCipher.sha256Hex(kind + "|" + (app == null ? "" : app.getId()) + "|" + objectType + "|" + objectId);
        if (found.containsKey(fp)) return;
        Violation v = new Violation();
        v.setId(Ids.newId());
        v.setFingerprint(fp);
        v.setPolicyKind(kind);
        v.setSeverity(severity);
        v.setApplicationId(app == null ? null : app.getId());
        v.setTeamId(app == null ? null : app.getTeamId());
        v.setObjectType(objectType);
        v.setObjectId(objectId);
        v.setLabel(label);
        v.setDetail(detail);
        v.setFirstSeenAt(now);
        v.setLastSeenAt(now);
        v.setStatus(ViolationStatus.OPEN);
        found.put(fp, v);
    }
}
