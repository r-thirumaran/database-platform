package org.dbplatform.controlplane.repo;

import org.dbplatform.controlplane.domain.CollectorState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectorStateRepository extends JpaRepository<CollectorState, String> {
}
