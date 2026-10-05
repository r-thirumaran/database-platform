package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Component;
import org.dbplatform.controlplane.domain.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComponentRepository extends JpaRepository<Component, String> {
    Optional<Component> findByComponentTypeAndComponentId(Enums.ComponentType type, String componentId);
    List<Component> findAllByOrderByComponentTypeAscComponentIdAsc();
    List<Component> findByComponentType(Enums.ComponentType type);
}
