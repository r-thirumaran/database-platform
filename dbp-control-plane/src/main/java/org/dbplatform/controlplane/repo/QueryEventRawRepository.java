package org.dbplatform.controlplane.repo;

import java.time.Instant;
import org.dbplatform.controlplane.domain.QueryEventRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueryEventRawRepository extends JpaRepository<QueryEventRaw, String> {
    long countByReceivedAtGreaterThanEqual(Instant since);

    @Modifying
    @Query("delete from QueryEventRaw e where e.receivedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
