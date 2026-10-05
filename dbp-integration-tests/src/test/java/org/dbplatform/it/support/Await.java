package org.dbplatform.it.support;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Polling helpers with generous timeouts and a readable failure message. */
public final class Await {
    private Await() {
    }

    public static void until(String what, Duration timeout, BooleanSupplier condition) {
        until(what, timeout, () -> condition.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty());
    }

    /** Polls {@code probe} every 250 ms until it yields a value; exceptions thrown by the probe are retried. */
    public static <T> T until(String what, Duration timeout, Supplier<Optional<T>> probe) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (true) {
            try {
                Optional<T> v = probe.get();
                if (v != null && v.isPresent()) {
                    return v.get();
                }
                last = null;
            } catch (Fatal e) {
                throw e;
            } catch (RuntimeException | AssertionError e) {
                last = e;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + timeout.toSeconds() + "s waiting for " + what
                        + (last == null ? "" : " (last error: " + last + ")"), last);
            }
            sleep(250);
        }
    }

    /** Thrown by a probe to abort the wait immediately (e.g. the process under observation died). */
    public static final class Fatal extends RuntimeException {
        public Fatal(String message) {
            super(message);
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
