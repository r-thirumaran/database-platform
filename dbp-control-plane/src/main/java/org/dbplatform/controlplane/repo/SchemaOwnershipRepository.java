package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.SchemaOwnership;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemaOwnershipRepository extends JpaRepository<SchemaOwnership, String> {
    Optional<SchemaOwnership> findByDatabaseIdAndSchemaIgnoreCase(String databaseId, String schema);
    List<SchemaOwnership> findByDatabaseId(String databaseId);
    void deleteByDatabaseId(String databaseId);
}
