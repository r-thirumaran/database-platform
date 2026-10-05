package org.dbplatform.proxy.support;

import java.util.function.BooleanSupplier;

public final class Await {
    private Await() {
    }

    public static void until(long timeoutMs, BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("timed out after " + timeoutMs + " ms waiting for " + what);
        }
    }
}
