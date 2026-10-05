package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Datasource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasourceRepository extends JpaRepository<Datasource, String> {
    Optional<Datasource> findByName(String name);
    List<Datasource> findAllByOrderByNameAsc();
    List<Datasource> findByOwnerTeamId(String ownerTeamId);
    List<Datasource> findByCurrentDatabaseIdOrTargetDatabaseId(String currentDatabaseId, String targetDatabaseId);
}
