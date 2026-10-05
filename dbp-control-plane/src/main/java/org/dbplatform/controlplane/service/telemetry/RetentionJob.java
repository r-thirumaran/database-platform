package org.dbplatform.controlplane.service.telemetry;

import java.time.Duration;
import java.time.Instant;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.repo.ConnectionEventRawRepository;
import org.dbplatform.controlplane.repo.PoolStatsSnapshotRepository;
import org.dbplatform.controlplane.repo.QueryEventRawRepository;
import org.dbplatform.controlplane.repo.QueryStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Deletes raw events / pool snapshots older than {@code DBP_TELEMETRY_RETENTION_HOURS} and old hourly stats. */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final DbpProperties props;
    private final QueryEventRawRepository rawQueries;
    private final ConnectionEventRawRepository rawConnections;
    private final PoolStatsSnapshotRepository pools;
    private final QueryStatRepository stats;

    public RetentionJob(DbpProperties props, QueryEventRawRepository rawQueries, ConnectionEventRawRepository rawConnections,
                        PoolStatsSnapshotRepository pools, QueryStatRepository stats) {
        this.props = props; this.rawQueries = rawQueries; this.rawConnections = rawConnections; this.pools = pools; this.stats = stats;
    }

    @Scheduled(initialDelayString = "${dbp.telemetry.cleanup-interval-seconds:600}000", fixedDelayString = "${dbp.telemetry.cleanup-interval-seconds:600}000")
    public void scheduled() { run(); }

    @Transactional
    public int run() {
        Instant rawBefore = Instant.now().minus(Duration.ofHours(props.getTelemetry().getRetentionHours()));
        Instant statsBefore = Instant.now().minus(Duration.ofDays(props.getTelemetry().getStatsRetentionDays()));
        int q = rawQueries.deleteOlderThan(rawBefore);
        int c = rawConnections.deleteOlderThan(rawBefore);
        int p = pools.deleteOlderThan(rawBefore);
        int s = stats.deleteOlderThan(statsBefore);
        int total = q + c + p + s;
        if (total > 0) log.info("Retention cleanup removed {} query events, {} connection events, {} pool snapshots, {} stat buckets", q, c, p, s);
        return total;
    }
}
