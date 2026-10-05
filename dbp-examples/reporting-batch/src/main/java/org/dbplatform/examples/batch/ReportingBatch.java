package org.dbplatform.examples.batch;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reporting batch: opens {@code DBP_BATCH_CONNECTIONS} connections, one per virtual thread, and keeps
 * every one of them busy with reporting SELECTs and small UPDATE batches for
 * {@code DBP_BATCH_DURATION_SECONDS}.
 * <p>
 * Run it direct, through the proxy and through the gateway and compare: direct/proxy show
 * N database sessions; through the gateway in TRANSACTION pool mode the control plane shows N
 * logical sessions but only a handful of physical connections.
 */
public final class ReportingBatch {

    private final BatchConfig config;
    private final Stats stats = new Stats();
    private final AtomicBoolean stop = new AtomicBoolean();
    private volatile long maxCustomerId = 200;
    private volatile long maxProductId = 60;

    public ReportingBatch(BatchConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        BatchConfig config;
        try {
            config = BatchConfig.fromEnv(System.getenv());
        } catch (IllegalArgumentException | NullPointerException e) {
            System.err.println("reporting-batch: " + e.getMessage());
            System.err.println("Required: DBP_JDBC_URL (and DBP_JDBC_USER / DBP_JDBC_PASSWORD). See BatchConfig for all variables.");
            System.exit(2);
            return;
        }
        int exit = new ReportingBatch(config).run();
        System.exit(exit);
    }

    public int run() throws InterruptedException {
        log("starting: url=%s user=%s engine=%s connections=%d duration=%ds driver=%s program=%s",
                config.maskedUrl(), config.user(), config.engine(), config.connections(),
                config.duration().toSeconds(), config.driverClass() == null ? "auto" : config.driverClass(),
                config.programName());

        if (config.driverClass() != null) {
            try {
                Class.forName(config.driverClass());
            } catch (ClassNotFoundException e) {
                log("driver class %s not found on the class path", config.driverClass());
                return 2;
            }
        }

        // One probe connection: fail fast with a clear message, and learn the id ranges.
        try (Connection probe = open()) {
            try (Statement st = probe.createStatement()) {
                try (ResultSet rs = st.executeQuery(config.engine().pingSql())) {
                    rs.next();
                }
                maxCustomerId = scalar(st, Workload.MAX_CUSTOMER_ID, 200);
                maxProductId = scalar(st, Workload.MAX_PRODUCT_ID, 60);
            }
            log("connected: %s %s (customers up to %d, products up to %d)",
                    probe.getMetaData().getDatabaseProductName(),
                    probe.getMetaData().getDatabaseProductVersion().lines().findFirst().orElse(""),
                    maxCustomerId, maxProductId);
        } catch (SQLException e) {
            log("cannot connect: %s", e.getMessage());
            return 1;
        }

        Instant start = Instant.now();
        Instant deadline = start.plus(config.duration());
        Thread reporter = startReporter(start);

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < config.connections(); i++) {
                int workerId = i;
                workers.submit(() -> worker(workerId, deadline));
            }
            workers.shutdown();
            if (!workers.awaitTermination(config.duration().toSeconds() + 120, TimeUnit.SECONDS)) {
                log("workers did not finish in time, forcing shutdown");
                stop.set(true);
            }
        }
        stop.set(true);
        reporter.interrupt();
        reporter.join(2000);

        double seconds = Math.max(1e-3, ChronoUnit.MILLIS.between(start, Instant.now()) / 1000.0);
        log("========== summary ==========");
        log("mode            : %s (%s)", config.maskedUrl(), config.engine());
        log("logical conns   : %d requested, %d opened, %d connect failures",
                config.connections(), stats.connectionsOpened(), stats.connectFailures());
        log("statements      : %d total (%d updates) in %.1fs = %.1f stmt/s",
                stats.statements(), stats.updates(), seconds, stats.statements() / seconds);
        log("rows fetched    : %d", stats.rows());
        log("latency ms      : p50=%.2f p95=%.2f p99=%.2f",
                stats.percentileMillis(50), stats.percentileMillis(95), stats.percentileMillis(99));
        log("errors          : %d", stats.errors());
        log("=============================");
        return stats.connectionsOpened() == 0 ? 1 : 0;
    }

    private void worker(int workerId, Instant deadline) {
        Connection con = null;
        long iteration = 0;
        while (!stop.get() && Instant.now().isBefore(deadline)) {
            try {
                if (con == null || con.isClosed()) {
                    con = open();
                }
                iteration++;
                runSelects(con);
                if (config.updateEvery() > 0 && config.updateBatchSize() > 0 && iteration % config.updateEvery() == 0) {
                    runUpdateBatch(con);
                }
                if (config.thinkMillis() > 0) {
                    Thread.sleep(config.thinkMillis());
                }
            } catch (SQLException e) {
                stats.error();
                if (iteration <= 1 || iteration % 100 == 0) {
                    log("worker %d: %s", workerId, firstLine(e.getMessage()));
                }
                closeQuietly(con);
                con = null;
                sleepQuietly(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        closeQuietly(con);
    }

    private void runSelects(Connection con) throws SQLException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < Workload.SELECTS.size(); i++) {
            String sql = Workload.SELECTS.get(i);
            long t0 = System.nanoTime();
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                switch (Workload.SELECT_PARAMS.get(i)) {
                    case CUSTOMER_ID -> ps.setLong(1, 1 + rnd.nextLong(Math.max(1, maxCustomerId)));
                    case TIMESTAMP -> ps.setTimestamp(1, Timestamp.from(Instant.now().minus(rnd.nextInt(1, 400), ChronoUnit.DAYS)));
                    case NONE -> { }
                }
                ps.setFetchSize(100);
                long rows = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                stats.statement((System.nanoTime() - t0) / 1000, rows);
            }
        }
    }

    private void runUpdateBatch(Connection con) throws SQLException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean previous = con.getAutoCommit();
        con.setAutoCommit(false);
        try (PreparedStatement ps = con.prepareStatement(Workload.UPDATE)) {
            for (int i = 0; i < config.updateBatchSize(); i++) {
                ps.setLong(1, 1 + rnd.nextLong(Math.max(1, maxProductId)));
                ps.addBatch();
            }
            ps.executeBatch();
            con.commit();
            stats.updateBatch(config.updateBatchSize());
        } catch (SQLException e) {
            try {
                con.rollback();
            } catch (SQLException ignored) {
                // connection is probably gone; the worker reconnects
            }
            throw e;
        } finally {
            con.setAutoCommit(previous);
        }
    }

    private Connection open() throws SQLException {
        try {
            Connection con = DriverManager.getConnection(config.jdbcUrl(), config.connectionProperties());
            try {
                con.setClientInfo("ApplicationName", config.programName());
            } catch (SQLException ignored) {
                // optional: not every driver accepts client info
            }
            stats.connectionOpened();
            return con;
        } catch (SQLException e) {
            stats.connectFailure();
            throw e;
        }
    }

    private Thread startReporter(Instant start) {
        Thread t = new Thread(() -> {
            long last = 0;
            while (!stop.get()) {
                try {
                    Thread.sleep(Math.max(1, config.reportIntervalSeconds()) * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                long now = stats.statements();
                double elapsed = ChronoUnit.MILLIS.between(start, Instant.now()) / 1000.0;
                log("t=%4.0fs statements=%d (+%d, %.0f stmt/s) errors=%d p95=%.1fms conns=%d",
                        elapsed, now, now - last, (now - last) / (double) Math.max(1, config.reportIntervalSeconds()),
                        stats.errors(), stats.percentileMillis(95), stats.connectionsOpened());
                last = now;
            }
        }, "reporter");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static long scalar(Statement st, String sql, long def) {
        try (ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                long v = rs.getLong(1);
                return v > 0 ? v : def;
            }
        } catch (SQLException e) {
            // fall through to the default
        }
        return def;
    }

    private static void closeQuietly(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException ignored) {
                // nothing to do
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void log(String fmt, Object... args) {
        System.out.printf(Locale.ROOT, "[reporting-batch] " + fmt + "%n", args);
    }
}
