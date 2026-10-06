package org.dbplatform.controlplane.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dbplatform.common.controlplane.ApplicationIdentity;
import org.dbplatform.common.controlplane.AuthRequest;
import org.dbplatform.common.controlplane.ConfigVersion;
import org.dbplatform.common.controlplane.CredentialMaterial;
import org.dbplatform.common.controlplane.DatasourceResolution;
import org.dbplatform.common.controlplane.PoolPolicy;
import org.dbplatform.common.controlplane.ProxyConfig;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.TelemetryJson;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.Credential;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.IdentityRules;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.CredentialRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.dbplatform.controlplane.service.ApplicationService;
import org.dbplatform.controlplane.service.ConfigVersionService;
import org.dbplatform.controlplane.service.CredentialService;
import org.dbplatform.controlplane.service.DatasourceService;
import org.dbplatform.controlplane.service.JdbcUrls;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolution endpoints for gateway and proxy (docs/control-plane-api.md §10). Responses are the
 * {@code org.dbplatform.common.controlplane} records the components deserialise; where the public
 * contract names extra fields (e.g. {@code poolPolicy.mode}) they are added on top so both readers work.
 */
@RestController
@RequestMapping("/api/v1/internal")
public class InternalResolutionController {
    /** Listener port per proxyable engine. H2 and OTHER databases have no proxy listener (the proxy speaks the Oracle, PostgreSQL and SQL Server wire protocols). */
    private static final Map<Enums.Engine, Integer> LISTENER_PORTS = Map.of(Enums.Engine.ORACLE, 1521, Enums.Engine.POSTGRES, 5432, Enums.Engine.MSSQL, 1433);

    private final ApplicationService applicationService;
    private final DatasourceService datasourceService;
    private final CredentialService credentialService;
    private final ConfigVersionService configVersion;
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DatasourceRepository datasources;
    private final DatabaseRepository databases;
    private final CredentialRepository credentials;
    private final AccessGrantRepository grants;

    public InternalResolutionController(ApplicationService applicationService, DatasourceService datasourceService, CredentialService credentialService,
                                        ConfigVersionService configVersion, TeamRepository teams, ApplicationRepository applications, DatasourceRepository datasources,
                                        DatabaseRepository databases, CredentialRepository credentials, AccessGrantRepository grants) {
        this.applicationService = applicationService; this.datasourceService = datasourceService; this.credentialService = credentialService;
        this.configVersion = configVersion; this.teams = teams; this.applications = applications; this.datasources = datasources;
        this.databases = databases; this.credentials = credentials; this.grants = grants;
    }

    @PostMapping("/auth/application")
    @Transactional
    public ApplicationIdentity auth(@RequestBody AuthRequest body) {
        Application app = applicationService.authenticate(body == null ? null : body.apiKey())
                .orElseThrow(() -> new ApiException.Unauthorized("Unknown or revoked api key"));
        String teamName = app.getTeamId() == null ? null : teams.findById(app.getTeamId()).map(Team::getName).orElse(null);
        return new ApplicationIdentity(app.getId(), app.getName(), app.getTeamId(), teamName, app.getTags());
    }

    @GetMapping("/resolve/datasource/{name}")
    @Transactional(readOnly = true)
    public ObjectNode resolve(@PathVariable String name, @RequestParam(required = false) String applicationId) {
        Datasource ds = datasources.findByName(name).orElseThrow(() -> new ApiException.NotFound("Datasource", name));
        Application app = null;
        AccessGrant grant = null;
        if (applicationId != null && !applicationId.isBlank()) {
            app = applications.findById(applicationId).orElseThrow(() -> new ApiException.Forbidden("Unknown application '" + applicationId + "'"));
            grant = grants.findFirstByApplicationIdAndDatasourceId(app.getId(), ds.getId()).filter(AccessGrant::isEnabled)
                    .orElseThrow(() -> new ApiException.Forbidden("Application '" + applicationId + "' has no enabled grant on datasource '" + name + "'"));
        }
        DatasourceService.Resolution res = datasourceService.resolve(ds, app, grant);
        DatabaseInstance db = res.database();
        if (db == null) throw new ApiException.Conflict("Datasource '" + name + "' has no current database");
        Optional<Credential> cred = db.getCredentialId() == null ? Optional.empty() : credentials.findById(db.getCredentialId());
        // pool mode precedence: AccessGrant.poolModeOverride wins over Datasource.poolPolicy.mode
        Enums.PoolMode poolMode = grant != null && grant.getPoolModeOverride() != null ? grant.getPoolModeOverride() : ds.getPoolPolicy().getMode();
        int maxLogical = grant != null && grant.getMaxLogicalConnections() != null ? grant.getMaxLogicalConnections() : ds.getPoolPolicy().getMaxConnections();
        org.dbplatform.controlplane.domain.PoolPolicy pp = ds.getPoolPolicy();
        DatasourceResolution r = new DatasourceResolution(
                new DatasourceResolution.DatasourceInfo(ds.getId(), ds.getName(), ds.getState().name()),
                new DatasourceResolution.GrantInfo(maxLogical, res.readOnly(), poolMode.name()),
                new DatasourceResolution.DatabaseInfo(db.getId(), db.getName(), Engine.parse(db.getEngine().name()), db.getHost(), db.getPort(), db.getServiceName(), JdbcUrls.of(db), db.getJdbcProperties()),
                cred.map(c -> new DatasourceResolution.CredentialRef(c.getId(), c.getUsername(), (int) c.getVersion())).orElse(null),
                new PoolPolicy(pp.getMinIdle(), pp.getMaxConnections(), pp.getConnectionTimeoutMs(), pp.getIdleTimeoutMs(), pp.getMaxLifetimeMs(), 5_000L,
                        pp.getValidationQuery(), null, pp.getStatementTimeoutSeconds() > 0 ? pp.getStatementTimeoutSeconds() * 1000L : null),
                configVersion.current());
        ObjectNode node = TelemetryJson.mapper().valueToTree(r);
        ObjectNode policy = (ObjectNode) node.get("poolPolicy");
        policy.put("mode", poolMode.name());
        policy.put("maxConnections", pp.getMaxConnections());
        policy.put("statementTimeoutSeconds", pp.getStatementTimeoutSeconds());
        if (res.rule() != null) node.put("routingRuleId", res.rule().getId());
        return node;
    }

    @GetMapping("/credentials/{id}/material")
    public CredentialMaterial material(@PathVariable String id, HttpServletRequest req) {
        CredentialService.Material m = credentialService.material(id, "internal:" + req.getRemoteAddr());
        return new CredentialMaterial(m.username(), m.secret(), (int) m.version());
    }

    @GetMapping("/config-version")
    public ConfigVersion version() { return new ConfigVersion(configVersion.current()); }

    @GetMapping("/proxy/config")
    @Transactional(readOnly = true)
    public ObjectNode proxyConfig(@RequestParam(required = false) String proxyId) {
        Map<String, DatabaseInstance> dbs = new LinkedHashMap<>();
        databases.findAllByOrderByNameAsc().forEach(d -> dbs.put(d.getId(), d));
        List<Datasource> dss = datasources.findAllByOrderByNameAsc();
        Map<Enums.Engine, List<ProxyConfig.Route>> routesPerEngine = new LinkedHashMap<>();
        for (Datasource ds : dss) {
            DatabaseInstance db = ds.getCurrentDatabaseId() == null ? null : dbs.get(ds.getCurrentDatabaseId());
            if (db == null || ds.getState() == Enums.DatasourceState.RETIRED || !LISTENER_PORTS.containsKey(db.getEngine())) continue;
            routesPerEngine.computeIfAbsent(db.getEngine(), k -> new ArrayList<>())
                    .add(new ProxyConfig.Route(ds.getName(), ds.getId(), db.getId(), db.getHost(), db.getPort(), db.getServiceName(), true));
        }
        List<ProxyConfig.Listener> listeners = new ArrayList<>();
        for (Enums.Engine engine : Enums.Engine.values()) {
            if (!LISTENER_PORTS.containsKey(engine)) continue; // H2 / OTHER: nothing to listen for
            List<DatabaseInstance> ofEngine = dbs.values().stream().filter(d -> d.getEngine() == engine).sorted(Comparator.comparing(DatabaseInstance::getName)).toList();
            if (ofEngine.isEmpty()) continue;
            DatabaseInstance dflt = ofEngine.get(0);
            listeners.add(new ProxyConfig.Listener(engine.name().toLowerCase() + "-main", Engine.parse(engine.name()), LISTENER_PORTS.get(engine),
                    routesPerEngine.getOrDefault(engine, List.of()),
                    new ProxyConfig.Route(null, null, dflt.getId(), dflt.getHost(), dflt.getPort(), null, false)));
        }
        List<ProxyConfig.ProxyApplication> apps = new ArrayList<>();
        Map<String, IdentityRules> rulesById = new LinkedHashMap<>();
        for (Application a : applications.findAllByOrderByNameAsc()) {
            rulesById.put(a.getId(), a.getIdentityRules());
            // the identity rules are written below in the public-API spelling; the dbp-common record is not serialised
            apps.add(new ProxyConfig.ProxyApplication(a.getId(), a.getName(), a.getTeamId(), null));
        }
        List<ProxyConfig.Quota> quotas = new ArrayList<>();
        for (AccessGrant g : grants.findAll()) {
            if (g.isEnabled() && g.getMaxProxyConnections() != null) quotas.add(new ProxyConfig.Quota(g.getApplicationId(), g.getDatasourceId(), g.getMaxProxyConnections()));
        }
        List<ProxyConfig.DatasourceQuota> dsQuotas = new ArrayList<>();
        for (Datasource ds : dss) {
            DatabaseInstance db = ds.getCurrentDatabaseId() == null ? null : dbs.get(ds.getCurrentDatabaseId());
            if (db != null && db.getMaxPhysicalConnections() != null) dsQuotas.add(new ProxyConfig.DatasourceQuota(ds.getId(), db.getMaxPhysicalConnections()));
        }
        ProxyConfig cfg = new ProxyConfig(configVersion.current(), listeners, apps, quotas, dsQuotas);
        ObjectNode node = TelemetryJson.mapper().valueToTree(cfg);
        // Identity rules: ONLY the public-API spelling (docs/control-plane-api.md §10). The dbp-common record spells the same
        // rules programs/machines/applicationNames, and a document carrying both spellings is rejected by the proxy's
        // Jackson record ("Should never call set() on setterless property"), so the node is replaced, not extended.
        for (JsonNode applicationNode : node.withArray("applications")) {
            IdentityRules ir = rulesById.get(applicationNode.get("id").asText());
            ((ObjectNode) applicationNode).set("identityRules", publicIdentityRules(ir));
        }
        if (proxyId != null) node.put("proxyId", proxyId);
        return node;
    }

    /** Identity rules of an application as the public API spells them; the proxy accepts this spelling directly. */
    static ObjectNode publicIdentityRules(IdentityRules ir) {
        ObjectNode rules = TelemetryJson.mapper().createObjectNode();
        rules.set("serviceAliases", stringArray(ir == null ? null : ir.getServiceAliases()));
        rules.set("programNames", stringArray(ir == null ? null : ir.getProgramNames()));
        rules.set("pgApplicationNames", stringArray(ir == null ? null : ir.getPgApplicationNames()));
        rules.set("machinePatterns", stringArray(ir == null ? null : ir.getMachinePatterns()));
        rules.set("cidrs", stringArray(ir == null ? null : ir.getCidrs()));
        return rules;
    }

    private static ArrayNode stringArray(List<String> values) {
        ArrayNode array = TelemetryJson.mapper().createArrayNode();
        if (values != null) values.forEach(array::add);
        return array;
    }
}
