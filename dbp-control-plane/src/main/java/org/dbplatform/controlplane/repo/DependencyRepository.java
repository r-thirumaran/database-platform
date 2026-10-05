package org.dbplatform.controlplane.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DependencyRepository extends JpaRepository<Dependency, String> {
    List<Dependency> findByFromId(String fromId);
    List<Dependency> findByToId(String toId);
    List<Dependency> findByFromIdIn(Collection<String> fromIds);
    List<Dependency> findByToIdIn(Collection<String> toIds);
    Optional<Dependency> findByFromTypeAndFromIdAndToTypeAndToIdAndKindAndSource(Enums.ObjectType fromType, String fromId,
            Enums.ObjectType toType, String toId, Enums.DependencyKind kind, Enums.DependencySource source);
    List<Dependency> findBySourceAndFromIdIn(Enums.DependencySource source, Collection<String> fromIds);
    void deleteByFromIdInOrToIdIn(Collection<String> fromIds, Collection<String> toIds);

    @Query("select d from Dependency d where (:fromId is null or d.fromId = :fromId)"
            + " and (:toId is null or d.toId = :toId) and (:kind is null or d.kind = :kind)")
    List<Dependency> search(@Param("fromId") String fromId, @Param("toId") String toId, @Param("kind") Enums.DependencyKind kind);
}
