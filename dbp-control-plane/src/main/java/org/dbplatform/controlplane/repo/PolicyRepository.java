package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, String> {
    Optional<Policy> findByKind(Enums.PolicyKind kind);
    List<Policy> findAllByOrderByKindAsc();
}
