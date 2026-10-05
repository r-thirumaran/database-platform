package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.DbColumn;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DbColumnRepository extends JpaRepository<DbColumn, String> {
    List<DbColumn> findByTableIdOrderByPositionAsc(String tableId);
    Optional<DbColumn> findByTableIdAndNameIgnoreCase(String tableId, String name);
    void deleteByTableId(String tableId);
}
