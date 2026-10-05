package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.CollectorState;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.CollectWhat;
import org.dbplatform.controlplane.repo.CollectorStateRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the dictionary crawler / runtime sampler / audit sampler of every database with
 * {@code collector.enabled}, each on its own interval, on virtual threads; one job per (database, kind) at a time.
 */
@Component
public class CollectorScheduler {
    private static final Logger log = LoggerFactory.getLogger(CollectorScheduler.class);

    private final DbpProperties props;
    private final DatabaseRepository databases;
    private final CollectorStateRepository states;
    private final CollectorConnections connections;
    private final CatalogueMerger catalogueMerger;
    private final RuntimeMerger runtimeMerger;
    private final AuditMerger auditMerger;
    private final LiveConnectionRegistry live;
    private final ExecutorService executor;
    private final TransactionTemplate tx;
    private final Map<Enums.Engine, DictionaryCrawler> crawlers = new EnumMap<>(Enums.Engine.class);
    private final Map<Enums.Engine, RuntimeSampler> samplers = new EnumMap<>(Enums.Engine.class);
    private final Map<Enums.Engine, AuditSampler> auditors = new EnumMap<>(Enums.Engine.class);
    private final Map<String, Map<CollectWhat, AtomicBoolean>> running = new ConcurrentHashMap<>();
    private final Map<String, RuntimeMerger.State> runtimeState = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastRun = new ConcurrentHashMap<>();

    public CollectorScheduler(DbpProperties props, DatabaseRepository databases, CollectorStateRepository states, CollectorConnections connections,
                              CatalogueMerger catalogueMerger, RuntimeMerger runtimeMerger, AuditMerger auditMerger, LiveConnectionRegistry live,
                              @Qualifier("collectorExecutor") ExecutorService executor, TransactionTemplate tx,
                              List<DictionaryCrawler> crawlerBeans, List<RuntimeSampler> samplerBeans, List<AuditSampler> auditBeans) {
        this.props = props; this.databases = databases; this.states = states; this.connections = connections; this.catalogueMerger = catalogueMerger;
        this.runtimeMerger = runtimeMerger; this.auditMerger = auditMerger; this.live = live; this.executor = executor; this.tx = tx;
        crawlerBeans.forEach(c -> crawlers.put(c.engine(), c));
        samplerBeans.forEach(s -> samplers.put(s.engine(), s));
        auditBeans.forEach(a -> auditors.put(a.engine(), a));
    }

    public record Status(Instant lastDictionaryRun, Instant lastRuntimeRun, Instant lastAuditRun, String lastError, int tablesSeen, int routinesSeen,
                         int sessionsSeen, boolean enabled, Set<String> running) {}

    @Scheduled(initialDelayString = "${dbp.collector.tick-seconds:5}000", fixedDelayString = "${dbp.collector.tick-seconds:5}000")
    public void tick() {
        if (!props.getCollector().isEnabled()) return;
        List<DatabaseInstance> all;
        try {
            all = databases.findAll();
        } catch (RuntimeException e) {
            log.debug("collector tick skipped: {}", e.toString());
            return;
        }
        Instant now = Instant.now();
        for (DatabaseInstance db : all) {
            if (!db.getCollector().isEnabled()) { live.clearCollectorSnapshot(db.getId()); continue; }
            if (due(db, CollectWhat.DICTIONARY, db.getCollector().getDictionaryIntervalSeconds(), now)) trigger(db, CollectWhat.DICTIONARY);
            if (due(db, CollectWhat.RUNTIME, db.getCollector().getRuntimeIntervalSeconds(), now)) trigger(db, CollectWhat.RUNTIME);
            if (db.getCollector().isAuditTrail() && due(db, CollectWhat.AUDIT, Math.max(60, db.getCollector().getRuntimeIntervalSeconds() * 4), now)) trigger(db, CollectWhat.AUDIT);
        }
    }

    private boolean due(DatabaseInstance db, CollectWhat what, int intervalSeconds, Instant now) {
        Instant last = lastRun.get(db.getId() + "|" + what);
        if (last == null) {
            CollectorState st = states.findById(db.getId()).orElse(null);
            if (st != null) last = switch (what) { case DICTIONARY -> st.getLastDictionaryRun(); case RUNTIME -> st.getLastRuntimeRun(); case AUDIT -> st.getLastAuditRun(); };
            if (last == null) return true;
            lastRun.put(db.getId() + "|" + what, last);
        }
        return !last.plusSeconds(Math.max(1, intervalSeconds)).isAfter(now);
    }

    /** Starts a job unless the same job is already running for this database. */
    public boolean trigger(DatabaseInstance db, CollectWhat what) {
        AtomicBoolean flag = running.computeIfAbsent(db.getId(), k -> new ConcurrentHashMap<>()).computeIfAbsent(what, k -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) return false;
        lastRun.put(db.getId() + "|" + what, Instant.now());
        executor.submit(() -> {
            try {
                run(db, what);
            } catch (Throwable t) {
                log.warn("Collector {} for database {} failed: {}", what, db.getName(), t.toString());
                recordError(db, t);
            } finally {
                flag.set(false);
            }
        });
        return true;
    }

    void run(DatabaseInstance db, CollectWhat what) throws Exception {
        switch (what) {
            case DICTIONARY -> {
                DictionaryCrawler crawler = crawlers.get(db.getEngine());
                if (crawler == null) throw new IllegalStateException("no dictionary crawler for " + db.getEngine());
                Model.CrawlResult result;
                try (Connection c = connections.open(db)) {
                    result = crawler.crawl(c, db, db.getCollector().getSchemas());
                }
                CatalogueMerger.MergeStats stats = catalogueMerger.apply(db, result);
                result.warnings.forEach(w -> log.info("[{}] dictionary: {}", db.getName(), w));
                log.info("[{}] dictionary crawl: {} tables, {} routines, {} dependencies ({} removed)", db.getName(), stats.tables(), stats.routines(), stats.dependencies(), stats.removedDependencies());
                update(db, st -> { st.setLastDictionaryRun(Instant.now()); st.setTablesSeen(stats.tables()); st.setRoutinesSeen(stats.routines()); st.setLastError(null); });
            }
            case RUNTIME -> {
                RuntimeSampler sampler = samplers.get(db.getEngine());
                if (sampler == null) throw new IllegalStateException("no runtime sampler for " + db.getEngine());
                RuntimeMerger.State state = runtimeState.computeIfAbsent(db.getId(), k -> new RuntimeMerger.State());
                Model.RuntimeSample sample;
                try (Connection c = connections.open(db)) {
                    sample = sampler.sample(c, db, Set.copyOf(state.tablesBySqlId.keySet()), props.getCollector().getMaxSqlPerSample());
                }
                RuntimeMerger.Result r = runtimeMerger.apply(db, sample, state);
                sample.warnings.forEach(w -> log.info("[{}] runtime: {}", db.getName(), w));
                log.debug("[{}] runtime sample: {} sessions, {} attributed, {} relationship updates", db.getName(), r.sessions(), r.attributed(), r.relationships());
                update(db, st -> { st.setLastRuntimeRun(Instant.now()); st.setSessionsSeen(r.sessions()); st.setLastError(null); });
            }
            case AUDIT -> {
                AuditSampler auditor = auditors.get(db.getEngine());
                if (auditor == null) throw new IllegalStateException("no audit sampler for " + db.getEngine());
                CollectorState st0 = states.findById(db.getId()).orElse(null);
                Instant since = st0 == null || st0.getAuditCursor() == null ? Instant.now().minusSeconds(3600) : st0.getAuditCursor();
                Model.AuditBatch batch;
                try (Connection c = connections.open(db)) {
                    batch = auditor.sample(c, db, db.getCollector().getSchemas(), since, 5000);
                }
                int n = auditMerger.apply(db, batch);
                log.info("[{}] audit: {} rows, {} relationship updates", db.getName(), batch.rows.size(), n);
                update(db, st -> { st.setLastAuditRun(Instant.now()); if (batch.cursor != null) st.setAuditCursor(batch.cursor); st.setLastError(null); });
            }
        }
    }

    private void recordError(DatabaseInstance db, Throwable t) {
        try {
            update(db, st -> st.setLastError(Instant.now() + " " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        } catch (RuntimeException e) {
            log.debug("could not record collector error: {}", e.toString());
        }
    }

    private void update(DatabaseInstance db, java.util.function.Consumer<CollectorState> fn) {
        tx.executeWithoutResult(s -> {
            CollectorState st = states.findById(db.getId()).orElseGet(() -> { CollectorState n = new CollectorState(); n.setDatabaseId(db.getId()); return n; });
            fn.accept(st);
            states.save(st);
        });
    }

    public Status status(DatabaseInstance db) {
        CollectorState st = states.findById(db.getId()).orElse(null);
        Set<String> runningNow = new java.util.TreeSet<>();
        running.getOrDefault(db.getId(), Map.of()).forEach((k, v) -> { if (v.get()) runningNow.add(k.name()); });
        return new Status(st == null ? null : st.getLastDictionaryRun(), st == null ? null : st.getLastRuntimeRun(), st == null ? null : st.getLastAuditRun(),
                st == null ? null : st.getLastError(), st == null ? 0 : st.getTablesSeen(), st == null ? 0 : st.getRoutinesSeen(), st == null ? 0 : st.getSessionsSeen(),
                props.getCollector().isEnabled() && db.getCollector().isEnabled(), runningNow);
    }
}
