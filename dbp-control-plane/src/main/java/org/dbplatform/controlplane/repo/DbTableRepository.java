package org.dbplatform.controlplane.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.DbTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DbTableRepository extends JpaRepository<DbTable, String> {
    Optional<DbTable> findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(String databaseId, String schema, String name);
    List<DbTable> findByDatabaseIdAndNameIgnoreCase(String databaseId, String name);
    List<DbTable> findByDatabaseId(String databaseId);
    List<DbTable> findByDatabaseIdOrderBySchemaAscNameAsc(String databaseId);
    List<DbTable> findByDatabaseIdAndSchemaIgnoreCase(String databaseId, String schema);
    List<DbTable> findByOwnerTeamId(String ownerTeamId);
    List<DbTable> findByProducerApplicationId(String producerApplicationId);
    List<DbTable> findByIdIn(Collection<String> ids);
    long countByOwnerTeamIdIsNull();
    long countByDatabaseId(String databaseId);
    void deleteByDatabaseId(String databaseId);

    @Query("select t from DbTable t where (:databaseId is null or t.databaseId = :databaseId)"
            + " and (:schema is null or upper(t.schema) = upper(:schema))"
            + " and (:ownerTeamId is null or t.ownerTeamId = :ownerTeamId)"
            + " and (:unowned = false or t.ownerTeamId is null)"
            + " and (:q is null or upper(t.name) like upper(:q) or upper(t.schema) like upper(:q))"
            + " order by t.schema asc, t.name asc")
    List<DbTable> search(@Param("databaseId") String databaseId, @Param("schema") String schema,
                         @Param("ownerTeamId") String ownerTeamId, @Param("unowned") boolean unowned, @Param("q") String q);
}
