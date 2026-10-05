package org.dbplatform.controlplane.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.QueryStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueryStatRepository extends JpaRepository<QueryStat, String> {
    @Query("select s from QueryStat s where s.sqlHash = :hash and s.bucketStart = :bucket"
            + " and ((:appId is null and s.applicationId is null) or s.applicationId = :appId)"
            + " and ((:dbId is null and s.databaseId is null) or s.databaseId = :dbId)")
    Optional<QueryStat> findBucket(@Param("hash") String sqlHash, @Param("appId") String applicationId,
                                   @Param("dbId") String databaseId, @Param("bucket") Instant bucketStart);

    @Query("select s from QueryStat s where s.bucketStart >= :since"
            + " and (:dbId is null or s.databaseId = :dbId) and (:appId is null or s.applicationId = :appId)")
    List<QueryStat> findSince(@Param("since") Instant since, @Param("dbId") String databaseId, @Param("appId") String applicationId);

    List<QueryStat> findByBucketStartGreaterThanEqual(Instant since);

    @Query("select s from QueryStat s where s.bucketStart >= :since and s.tablesJson like :tableIdPattern")
    List<QueryStat> findSinceForTable(@Param("since") Instant since, @Param("tableIdPattern") String tableIdPattern);

    @Modifying
    @Query("delete from QueryStat s where s.bucketStart < :before")
    int deleteOlderThan(@Param("before") Instant before);

    @Query("select coalesce(sum(s.execCount), 0) from QueryStat s where s.bucketStart >= :since")
    long sumExecSince(@Param("since") Instant since);
}
