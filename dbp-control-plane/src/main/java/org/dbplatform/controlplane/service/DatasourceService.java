package org.dbplatform.controlplane.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.MigrationEvent;
import org.dbplatform.controlplane.domain.RoutingRule;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.MigrationEventRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DatasourceService {
    private final DatasourceRepository datasources;
    private final DatabaseRepository databases;
    private final TeamRepository teams;
    private final AccessGrantRepository grants;
    private final MigrationEventRepository migrationEvents;
    private final ConfigVersionService configVersion;

    public DatasourceService(DatasourceRepository datasources, DatabaseRepository databases, TeamRepository teams,
                             AccessGrantRepository grants, MigrationEventRepository migrationEvents, ConfigVersionService configVersion) {
        this.datasources = datasources; this.databases = databases; this.teams = teams; this.grants = grants;
        this.migrationEvents = migrationEvents; this.configVersion = configVersion;
    }

    @Transactional(readOnly = true)
    public List<Datasource> list() { return datasources.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public Datasource get(String id) { return datasources.findById(id).orElseThrow(() -> new ApiException.NotFound("Datasource", id)); }

    @Transactional(readOnly = true)
    public Optional<Datasource> findByName(String name) { return datasources.findByName(name); }

    public Datasource create(Datasource in) {
        datasources.findByName(in.getName()).ifPresent(d -> { throw new ApiException.Conflict("Datasource '" + in.getName() + "' already exists"); });
        Datasource d = new Datasource();
        d.setId(Ids.newId());
        d.setCreatedAt(Instant.now());
        copy(in, d);
        d.setRoutingRules(normalizeRules(in.getRoutingRules()));
        configVersion.bump();
        return datasources.save(d);
    }

    public Datasource update(String id, Datasource in) {
        Datasource d = get(id);
        datasources.findByName(in.getName()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ApiException.Conflict("Datasource '" + in.getName() + "' already exists"); });
        copy(in, d);
        if (in.getRoutingRules() != null) replaceRulesInPlace(d, in.getRoutingRules());
        configVersion.bump();
        return datasources.save(d);
    }

    public Datasource upsertByName(Datasource in) {
        return datasources.findByName(in.getName()).map(e -> update(e.getId(), in)).orElseGet(() -> create(in));
    }

    public void delete(String id) {
        Datasource d = get(id);
        grants.deleteByDatasourceId(id);
        migrationEvents.deleteByDatasourceId(id);
        datasources.delete(d);
        configVersion.bump();
    }

    public Datasource replaceRules(String id, List<RoutingRule> rules) {
        Datasource d = get(id);
        replaceRulesInPlace(d, rules);
        configVersion.bump();
        return datasources.save(d);
    }

    public Datasource addRule(String id, RoutingRule rule) {
        Datasource d = get(id);
        RoutingRule r = normalizeRule(rule);
        d.getRoutingRules().add(r);
        d.getRoutingRules().sort(Comparator.comparingInt(RoutingRule::getPriority));
        d.setUpdatedAt(Instant.now());
        configVersion.bump();
        return datasources.save(d);
    }

    public Datasource deleteRule(String id, String ruleId) {
        Datasource d = get(id);
        if (!d.getRoutingRules().removeIf(r -> ruleId.equals(r.getId()))) throw new ApiException.NotFound("RoutingRule", ruleId);
        d.setUpdatedAt(Instant.now());
        configVersion.bump();
        return datasources.save(d);
    }

    /** Sets {@code currentDatabaseId} and records a MigrationEvent. */
    public Datasource switchDatabase(String id, String databaseId, String by, String note) {
        Datasource d = get(id);
        DatabaseInstance target = databases.findById(databaseId).orElseThrow(() -> new ApiException.BadRequest("Unknown databaseId '" + databaseId + "'"));
        MigrationEvent ev = new MigrationEvent();
        ev.setId(Ids.newId());
        ev.setDatasourceId(d.getId());
        ev.setFromDatabaseId(d.getCurrentDatabaseId());
        ev.setToDatabaseId(target.getId());
        ev.setAt(Instant.now());
        ev.setBy(by);
        ev.setNote(note != null ? note : "switched current database to " + target.getName());
        migrationEvents.save(ev);
        d.setCurrentDatabaseId(target.getId());
        if (target.getId().equals(d.getTargetDatabaseId())) {
            d.setTargetDatabaseId(null);
            if (d.getState() == Enums.DatasourceState.MIGRATING) d.setState(Enums.DatasourceState.ACTIVE);
        }
        d.setUpdatedAt(Instant.now());
        configVersion.bump();
        return datasources.save(d);
    }

    // ---- resolution ------------------------------------------------------------------------------

    public record Resolution(Datasource datasource, DatabaseInstance database, RoutingRule rule, boolean readOnly) {}

    /**
     * Routing precedence (docs/control-plane-api.md §5): first enabled rule (priority ascending) whose
     * applicationId matches, else first enabled rule whose tag matches one of the application's tags,
     * else {@code currentDatabaseId}.
     */
    @Transactional(readOnly = true)
    public Resolution resolve(Datasource ds, Application app, AccessGrant grant) {
        List<RoutingRule> rules = ds.getRoutingRules().stream().filter(RoutingRule::isEnabled)
                .sorted(Comparator.comparingInt(RoutingRule::getPriority)).toList();
        RoutingRule chosen = null;
        if (app != null) {
            chosen = rules.stream().filter(r -> app.getId().equals(r.getApplicationId())).findFirst().orElse(null);
            if (chosen == null) {
                chosen = rules.stream().filter(r -> r.getTag() != null && r.getApplicationId() == null && app.getTags().contains(r.getTag()))
                        .findFirst().orElse(null);
            }
        }
        String dbId = chosen != null ? chosen.getDatabaseId() : ds.getCurrentDatabaseId();
        DatabaseInstance db = dbId == null ? null : databases.findById(dbId).orElse(null);
        boolean readOnly = (chosen != null && chosen.isReadOnly()) || (grant != null && grant.isReadOnly());
        return new Resolution(ds, db, chosen, readOnly);
    }

    @Transactional(readOnly = true)
    public DatabaseInstance currentDatabase(Datasource ds) {
        return ds.getCurrentDatabaseId() == null ? null : databases.findById(ds.getCurrentDatabaseId()).orElse(null);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void copy(Datasource in, Datasource d) {
        if (in.getOwnerTeamId() != null && teams.findById(in.getOwnerTeamId()).isEmpty()) throw new ApiException.BadRequest("Unknown ownerTeamId '" + in.getOwnerTeamId() + "'");
        if (in.getCurrentDatabaseId() != null && databases.findById(in.getCurrentDatabaseId()).isEmpty()) throw new ApiException.BadRequest("Unknown currentDatabaseId '" + in.getCurrentDatabaseId() + "'");
        if (in.getTargetDatabaseId() != null && databases.findById(in.getTargetDatabaseId()).isEmpty()) throw new ApiException.BadRequest("Unknown targetDatabaseId '" + in.getTargetDatabaseId() + "'");
        d.setName(in.getName());
        d.setDisplayName(in.getDisplayName() != null ? in.getDisplayName() : in.getName());
        d.setOwnerTeamId(in.getOwnerTeamId());
        d.setState(in.getState() == null ? Enums.DatasourceState.ACTIVE : in.getState());
        d.setCurrentDatabaseId(in.getCurrentDatabaseId());
        d.setTargetDatabaseId(in.getTargetDatabaseId());
        d.setPoolPolicy(in.getPoolPolicy());
        d.setDescription(in.getDescription());
        d.setTags(in.getTags());
        d.setUpdatedAt(Instant.now());
    }

    /**
     * Replaces the rule list: rules whose id is already present are updated in place (the UI sends the full list back
     * with ids when it edits or toggles one), unknown ids are added, missing ones are removed (orphanRemoval).
     */
    private void replaceRulesInPlace(Datasource d, List<RoutingRule> rules) {
        List<RoutingRule> fresh = normalizeRules(rules);
        Map<String, RoutingRule> incoming = new LinkedHashMap<>();
        for (RoutingRule r : fresh) incoming.put(r.getId(), r);
        List<RoutingRule> current = d.getRoutingRules();
        current.removeIf(r -> !incoming.containsKey(r.getId()));
        for (RoutingRule existing : current) {
            RoutingRule f = incoming.remove(existing.getId());
            existing.setPriority(f.getPriority());
            existing.setApplicationId(f.getApplicationId());
            existing.setTag(f.getTag());
            existing.setDatabaseId(f.getDatabaseId());
            existing.setReadOnly(f.isReadOnly());
            existing.setEnabled(f.isEnabled());
        }
        current.addAll(incoming.values());
        current.sort(Comparator.comparingInt(RoutingRule::getPriority));
        d.setUpdatedAt(Instant.now());
    }

    private List<RoutingRule> normalizeRules(List<RoutingRule> rules) {
        List<RoutingRule> out = new ArrayList<>();
        if (rules != null) for (RoutingRule r : rules) out.add(normalizeRule(r));
        out.sort(Comparator.comparingInt(RoutingRule::getPriority));
        return out;
    }

    private RoutingRule normalizeRule(RoutingRule in) {
        if (in.getDatabaseId() == null || databases.findById(in.getDatabaseId()).isEmpty()) {
            throw new ApiException.BadRequest("Routing rule references unknown databaseId '" + in.getDatabaseId() + "'");
        }
        if (in.getApplicationId() == null && (in.getTag() == null || in.getTag().isBlank())) {
            throw new ApiException.BadRequest("Routing rule needs an applicationId or a tag");
        }
        RoutingRule r = new RoutingRule();
        r.setId(in.getId() != null && !in.getId().isBlank() ? in.getId() : Ids.newId());
        r.setPriority(in.getPriority());
        r.setApplicationId(in.getApplicationId());
        r.setTag(in.getTag());
        r.setDatabaseId(in.getDatabaseId());
        r.setReadOnly(in.isReadOnly());
        r.setEnabled(in.isEnabled());
        return r;
    }
}
