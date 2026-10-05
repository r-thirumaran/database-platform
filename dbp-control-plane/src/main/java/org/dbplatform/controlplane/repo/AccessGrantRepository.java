package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, String> {
    List<AccessGrant> findByApplicationId(String applicationId);
    List<AccessGrant> findByDatasourceId(String datasourceId);
    List<AccessGrant> findByApplicationIdAndDatasourceId(String applicationId, String datasourceId);
    Optional<AccessGrant> findFirstByApplicationIdAndDatasourceId(String applicationId, String datasourceId);
    void deleteByApplicationId(String applicationId);
    void deleteByDatasourceId(String datasourceId);
}
