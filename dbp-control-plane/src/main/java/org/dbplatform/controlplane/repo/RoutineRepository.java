package org.dbplatform.controlplane.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Routine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineRepository extends JpaRepository<Routine, String> {
    Optional<Routine> findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(String databaseId, String schema, String name);
    List<Routine> findByDatabaseIdAndNameIgnoreCase(String databaseId, String name);
    List<Routine> findByDatabaseId(String databaseId);
    List<Routine> findByTriggerTableId(String triggerTableId);
    List<Routine> findByIdIn(Collection<String> ids);
    List<Routine> findByOwnerTeamId(String ownerTeamId);
    long countByDatabaseId(String databaseId);
    void deleteByDatabaseId(String databaseId);

    @Query("select r from Routine r where (:databaseId is null or r.databaseId = :databaseId)"
            + " and (:schema is null or upper(r.schema) = upper(:schema))"
            + " and (:kind is null or r.kind = :kind)"
            + " and (:q is null or upper(r.name) like upper(:q))"
            + " order by r.schema asc, r.name asc")
    List<Routine> search(@Param("databaseId") String databaseId, @Param("schema") String schema,
                         @Param("kind") Enums.RoutineKind kind, @Param("q") String q);
}
