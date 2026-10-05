package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatabaseRepository extends JpaRepository<DatabaseInstance, String> {
    Optional<DatabaseInstance> findByName(String name);
    List<DatabaseInstance> findAllByOrderByNameAsc();
    List<DatabaseInstance> findByEngineOrderByNameAsc(Enums.Engine engine);
    long countByCredentialId(String credentialId);
}
