package org.dbplatform.controlplane.repo;

import java.time.Instant;
import org.dbplatform.controlplane.domain.ConnectionEventRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionEventRawRepository extends JpaRepository<ConnectionEventRaw, String> {
    @Modifying
    @Query("delete from ConnectionEventRaw e where e.receivedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
