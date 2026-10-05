package org.dbplatform.controlplane.repo;

import java.util.List;
import org.dbplatform.controlplane.domain.MigrationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationEventRepository extends JpaRepository<MigrationEvent, String> {
    List<MigrationEvent> findByDatasourceIdOrderByAtDesc(String datasourceId);
    List<MigrationEvent> findAllByOrderByAtDesc();
    void deleteByDatasourceId(String datasourceId);
}
