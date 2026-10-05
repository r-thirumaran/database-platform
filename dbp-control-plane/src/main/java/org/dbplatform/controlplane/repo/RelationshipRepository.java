package org.dbplatform.controlplane.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Relationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationshipRepository extends JpaRepository<Relationship, String> {
    List<Relationship> findByApplicationId(String applicationId);
    List<Relationship> findByObjectId(String objectId);
    List<Relationship> findByObjectIdIn(Collection<String> objectIds);
    List<Relationship> findByViaRoutineId(String viaRoutineId);
    List<Relationship> findByObjectIdAndKind(String objectId, Enums.RelationshipKind kind);
    void deleteByApplicationId(String applicationId);
    void deleteByObjectIdIn(Collection<String> objectIds);

    @Query("select r from Relationship r where r.applicationId = :applicationId and r.objectType = :objectType"
            + " and r.objectId = :objectId and r.kind = :kind and r.source = :source"
            + " and ((:via is null and r.viaRoutineId is null) or r.viaRoutineId = :via)")
    Optional<Relationship> findExisting(@Param("applicationId") String applicationId, @Param("objectType") Enums.ObjectType objectType,
                                        @Param("objectId") String objectId, @Param("kind") Enums.RelationshipKind kind,
                                        @Param("source") Enums.RelationshipSource source, @Param("via") String viaRoutineId);

    @Query("select r from Relationship r where (:applicationId is null or r.applicationId = :applicationId)"
            + " and (:objectId is null or r.objectId = :objectId) and (:kind is null or r.kind = :kind)"
            + " and (:source is null or r.source = :source) order by r.lastSeenAt desc")
    List<Relationship> search(@Param("applicationId") String applicationId, @Param("objectId") String objectId,
                              @Param("kind") Enums.RelationshipKind kind, @Param("source") Enums.RelationshipSource source);

    @Query("select distinct r.objectId from Relationship r where r.objectType = 'TABLE' and r.lastSeenAt >= :since")
    List<String> tableIdsSeenSince(@Param("since") Instant since);
}
