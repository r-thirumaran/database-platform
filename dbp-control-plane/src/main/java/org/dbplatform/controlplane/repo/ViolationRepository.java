package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Violation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViolationRepository extends JpaRepository<Violation, String> {
    Optional<Violation> findByFingerprint(String fingerprint);
    List<Violation> findByStatusOrderBySeverityDescLastSeenAtDesc(Enums.ViolationStatus status);
    List<Violation> findAllByOrderByLastSeenAtDesc();
    long countByStatus(Enums.ViolationStatus status);
    void deleteByApplicationId(String applicationId);
}
