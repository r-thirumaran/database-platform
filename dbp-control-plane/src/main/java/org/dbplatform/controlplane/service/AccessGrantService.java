package org.dbplatform.controlplane.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.repo.AccessGrantRepository;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccessGrantService {
    private final AccessGrantRepository grants;
    private final ApplicationRepository applications;
    private final DatasourceRepository datasources;
    private final ConfigVersionService configVersion;

    public AccessGrantService(AccessGrantRepository grants, ApplicationRepository applications, DatasourceRepository datasources, ConfigVersionService configVersion) {
        this.grants = grants; this.applications = applications; this.datasources = datasources; this.configVersion = configVersion;
    }

    @Transactional(readOnly = true)
    public List<AccessGrant> list(String applicationId, String datasourceId) {
        if (applicationId != null && datasourceId != null) return grants.findByApplicationIdAndDatasourceId(applicationId, datasourceId);
        if (applicationId != null) return grants.findByApplicationId(applicationId);
        if (datasourceId != null) return grants.findByDatasourceId(datasourceId);
        return grants.findAll();
    }

    @Transactional(readOnly = true)
    public AccessGrant get(String id) { return grants.findById(id).orElseThrow(() -> new ApiException.NotFound("AccessGrant", id)); }

    @Transactional(readOnly = true)
    public Optional<AccessGrant> find(String applicationId, String datasourceId) {
        return grants.findFirstByApplicationIdAndDatasourceId(applicationId, datasourceId);
    }

    public AccessGrant create(AccessGrant in) {
        validateRefs(in);
        Optional<AccessGrant> existing = grants.findFirstByApplicationIdAndDatasourceId(in.getApplicationId(), in.getDatasourceId());
        if (existing.isPresent()) throw new ApiException.Conflict("A grant for this application and datasource already exists (" + existing.get().getId() + ")");
        AccessGrant g = new AccessGrant();
        g.setId(Ids.newId());
        g.setCreatedAt(Instant.now());
        copy(in, g);
        configVersion.bump();
        return grants.save(g);
    }

    public AccessGrant update(String id, AccessGrant in) {
        AccessGrant g = get(id);
        validateRefs(in);
        grants.findFirstByApplicationIdAndDatasourceId(in.getApplicationId(), in.getDatasourceId()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ApiException.Conflict("A grant for this application and datasource already exists (" + o.getId() + ")"); });
        copy(in, g);
        configVersion.bump();
        return grants.save(g);
    }

    /** Upsert by (applicationId, datasourceId). */
    public AccessGrant upsert(AccessGrant in) {
        return grants.findFirstByApplicationIdAndDatasourceId(in.getApplicationId(), in.getDatasourceId())
                .map(e -> update(e.getId(), in)).orElseGet(() -> create(in));
    }

    public void delete(String id) {
        grants.delete(get(id));
        configVersion.bump();
    }

    private void validateRefs(AccessGrant in) {
        if (applications.findById(in.getApplicationId()).isEmpty()) throw new ApiException.BadRequest("Unknown applicationId '" + in.getApplicationId() + "'");
        if (datasources.findById(in.getDatasourceId()).isEmpty()) throw new ApiException.BadRequest("Unknown datasourceId '" + in.getDatasourceId() + "'");
    }

    private static void copy(AccessGrant in, AccessGrant g) {
        g.setApplicationId(in.getApplicationId());
        g.setDatasourceId(in.getDatasourceId());
        g.setMaxLogicalConnections(in.getMaxLogicalConnections());
        g.setMaxProxyConnections(in.getMaxProxyConnections());
        g.setPoolModeOverride(in.getPoolModeOverride());
        g.setReadOnly(in.isReadOnly());
        g.setEnabled(in.isEnabled());
        g.setNote(in.getNote());
        g.setUpdatedAt(Instant.now());
    }
}
