package org.dbplatform.it.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Samples {@code pg_stat_activity} every ~50 ms on a dedicated superuser connection and remembers the highest number
 * of backends of one role on one database: the ground truth for "many logical connections, few physical ones".
 */
public final class BackendSampler implements AutoCloseable {
    private final Connection superuser;
    private final String role;
    private final String database;
    private final AtomicInteger max = new AtomicInteger();
    private final AtomicInteger samples = new AtomicInteger();
    private volatile boolean running = true;
    private volatile Exception failure;
    private final Thread thread;

    public BackendSampler(Connection superuser, String role, String database) {
        this.superuser = superuser;
        this.role = role;
        this.database = database;
        this.thread = new Thread(this::loop, "pg-backend-sampler");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        try (PreparedStatement ps = superuser.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND datname = ? AND backend_type = 'client backend'")) {
            ps.setString(1, role);
            ps.setString(2, database);
            while (running) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int n = rs.getInt(1);
                        max.accumulateAndGet(n, Math::max);
                        samples.incrementAndGet();
                    }
                }
                Thread.sleep(50);
            }
        } catch (SQLException e) {
            failure = e;
        } catch (InterruptedException ignored) {
            // stopped
        }
    }

    public int max() {
        return max.get();
    }

    public int samples() {
        return samples.get();
    }

    public Exception failure() {
        return failure;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
