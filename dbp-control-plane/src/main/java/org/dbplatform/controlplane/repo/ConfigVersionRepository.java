package org.dbplatform.controlplane.repo;

import java.time.Instant;
import org.dbplatform.controlplane.domain.ConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConfigVersionRepository extends JpaRepository<ConfigVersion, Integer> {
    @Modifying
    @Query("update ConfigVersion c set c.version = c.version + 1, c.updatedAt = :now where c.id = 1")
    int bump(@Param("now") Instant now);

    @Query("select c.version from ConfigVersion c where c.id = 1")
    Long current();
}
