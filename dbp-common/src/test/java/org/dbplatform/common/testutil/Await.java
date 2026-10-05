package org.dbplatform.common.testutil;

import java.time.Duration;
import java.util.concurrent.Callable;

/** Minimal polling helper for asynchronous assertions (no external dependency). */
public final class Await {

    private Duration timeout = Duration.ofSeconds(5);

    private Await() {}

    public static Await await() {
        return new Await();
    }

    public Await atMost(Duration d) {
        this.timeout = d;
        return this;
    }

    public void until(Callable<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
            } catch (Exception e) {
                throw new AssertionError("condition threw", e);
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
    }
}
