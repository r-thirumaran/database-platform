package org.dbplatform.controlplane.repo;

import java.time.Instant;
import java.util.List;
import org.dbplatform.controlplane.domain.PoolStatsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PoolStatsSnapshotRepository extends JpaRepository<PoolStatsSnapshot, String> {
    List<PoolStatsSnapshot> findByRecordedAtGreaterThanEqualOrderByRecordedAtDesc(Instant since);

    @Modifying
    @Query("delete from PoolStatsSnapshot p where p.recordedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
