package org.dbplatform.examples.batch;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe counters plus a reservoir sample of statement latencies. */
public final class Stats {

    private static final int RESERVOIR = 20_000;

    private final LongAdder statements = new LongAdder();
    private final LongAdder updates = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder rows = new LongAdder();
    private final AtomicLong seen = new AtomicLong();
    private final long[] latenciesMicros = new long[RESERVOIR];
    private final AtomicLong connectionsOpened = new AtomicLong();
    private final AtomicLong connectFailures = new AtomicLong();

    public void statement(long micros, long rowCount) {
        statements.increment();
        rows.add(rowCount);
        long n = seen.getAndIncrement();
        if (n < RESERVOIR) {
            latenciesMicros[(int) n] = micros;
        } else {
            long r = ThreadLocalRandom.current().nextLong(n + 1);
            if (r < RESERVOIR) {
                latenciesMicros[(int) r] = micros;
            }
        }
    }

    public void updateBatch(int size) {
        updates.add(size);
        statements.add(size);
    }

    public void error() {
        errors.increment();
    }

    public void connectionOpened() {
        connectionsOpened.incrementAndGet();
    }

    public void connectFailure() {
        connectFailures.incrementAndGet();
    }

    public long statements() {
        return statements.sum();
    }

    public long updates() {
        return updates.sum();
    }

    public long errors() {
        return errors.sum();
    }

    public long rows() {
        return rows.sum();
    }

    public long connectionsOpened() {
        return connectionsOpened.get();
    }

    public long connectFailures() {
        return connectFailures.get();
    }

    /** Percentile in milliseconds over the reservoir (0 when nothing was recorded). */
    public double percentileMillis(double p) {
        int n = (int) Math.min(seen.get(), RESERVOIR);
        if (n == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(latenciesMicros, n);
        Arrays.sort(copy);
        int idx = (int) Math.min(n - 1, Math.max(0, Math.round(p / 100.0 * n) - 1));
        return copy[idx] / 1000.0;
    }
}
