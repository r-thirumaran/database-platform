package org.dbplatform.controlplane.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.ApiKey;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.ApiKeyRepository;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.dbplatform.controlplane.repo.ViolationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ApplicationService {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ApplicationRepository applications;
    private final TeamRepository teams;
    private final ApiKeyRepository apiKeys;
    private final AccessGrantRepository grants;
    private final RelationshipRepository relationships;
    private final DatasourceRepository datasources;
    private final DbTableRepository tables;
    private final ViolationRepository violations;
    private final ConfigVersionService configVersion;
    private final SecureRandom random = new SecureRandom();

    public ApplicationService(ApplicationRepository applications, TeamRepository teams, ApiKeyRepository apiKeys,
                              AccessGrantRepository grants, RelationshipRepository relationships, DatasourceRepository datasources,
                              DbTableRepository tables, ViolationRepository violations, ConfigVersionService configVersion) {
        this.applications = applications; this.teams = teams; this.apiKeys = apiKeys; this.grants = grants;
        this.relationships = relationships; this.datasources = datasources; this.tables = tables;
        this.violations = violations; this.configVersion = configVersion;
    }

    @Transactional(readOnly = true)
    public List<Application> list() { return applications.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public Application get(String id) { return applications.findById(id).orElseThrow(() -> new ApiException.NotFound("Application", id)); }

    public Application create(Application in) {
        applications.findByName(in.getName()).ifPresent(a -> { throw new ApiException.Conflict("Application '" + in.getName() + "' already exists"); });
        Application a = new Application();
        a.setId(Ids.newId());
        a.setCreatedAt(Instant.now());
        copy(in, a);
        configVersion.bump();
        return applications.save(a);
    }

    public Application update(String id, Application in) {
        Application a = get(id);
        applications.findByName(in.getName()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ApiException.Conflict("Application '" + in.getName() + "' already exists"); });
        copy(in, a);
        configVersion.bump();
        return applications.save(a);
    }

    public Application upsertByName(Application in) {
        return applications.findByName(in.getName()).map(e -> update(e.getId(), in)).orElseGet(() -> create(in));
    }

    public void delete(String id) {
        Application a = get(id);
        apiKeys.deleteByApplicationId(id);
        grants.deleteByApplicationId(id);
        relationships.deleteByApplicationId(id);
        violations.deleteByApplicationId(id);
        for (Datasource ds : datasources.findAll()) {
            if (ds.getRoutingRules().removeIf(r -> id.equals(r.getApplicationId()))) datasources.save(ds);
        }
        tables.findByProducerApplicationId(id).forEach(t -> { t.setProducerApplicationId(null); t.setProducerSource(null); });
        applications.delete(a);
        configVersion.bump();
    }

    private void copy(Application in, Application a) {
        if (in.getTeamId() != null && teams.findById(in.getTeamId()).isEmpty()) {
            throw new ApiException.BadRequest("Unknown teamId '" + in.getTeamId() + "'");
        }
        a.setName(in.getName());
        a.setDisplayName(in.getDisplayName() != null ? in.getDisplayName() : in.getName());
        a.setTeamId(in.getTeamId());
        a.setKind(in.getKind());
        a.setDescription(in.getDescription());
        a.setRuntime(in.getRuntime());
        a.setIdentityRules(in.getIdentityRules());
        a.setTags(in.getTags());
        a.setUpdatedAt(Instant.now());
    }

    // ---- API keys -------------------------------------------------------------------------------

    public record IssuedKey(String id, String prefix, String apiKey) {}

    @Transactional(readOnly = true)
    public List<ApiKey> listKeys(String applicationId) {
        get(applicationId);
        return apiKeys.findByApplicationIdOrderByCreatedAtAsc(applicationId);
    }

    /** Issues {@code dbp_<prefix>_<secret>} (prefix = first 8 chars of the key id); only sha-256(full key) is stored. */
    public IssuedKey issueKey(String applicationId, String label) {
        get(applicationId);
        String id = Ids.newId();
        String prefix = id.substring(0, 8);           // contract: prefix = first 8 chars of the key id
        String secret = randomString(32);
        String full = "dbp_" + prefix + "_" + secret;
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setApplicationId(applicationId);
        k.setPrefix(prefix);
        k.setKeyHash(SecretCipher.sha256Hex(full));
        k.setLabel(label);
        k.setCreatedAt(Instant.now());
        apiKeys.save(k);
        configVersion.bump();
        return new IssuedKey(k.getId(), prefix, full);
    }

    public void revokeKey(String applicationId, String keyId) {
        ApiKey k = apiKeys.findById(keyId).filter(x -> x.getApplicationId().equals(applicationId))
                .orElseThrow(() -> new ApiException.NotFound("ApiKey", keyId));
        if (k.getRevokedAt() == null) {
            k.setRevokedAt(Instant.now());
            apiKeys.save(k);
            configVersion.bump();
        }
    }

    /** Resolves a plaintext api key to its (non-revoked) application; touches {@code lastUsedAt}. */
    public Optional<Application> authenticate(String apiKey) {
        if (apiKey == null || !apiKey.startsWith("dbp_")) return Optional.empty();
        return apiKeys.findByKeyHash(SecretCipher.sha256Hex(apiKey))
                .filter(k -> k.getRevokedAt() == null)
                .flatMap(k -> {
                    k.setLastUsedAt(Instant.now());
                    apiKeys.save(k);
                    return applications.findById(k.getApplicationId());
                });
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
