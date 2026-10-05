package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    List<ApiKey> findByApplicationIdOrderByCreatedAtAsc(String applicationId);
    Optional<ApiKey> findByKeyHash(String keyHash);
    void deleteByApplicationId(String applicationId);
}
