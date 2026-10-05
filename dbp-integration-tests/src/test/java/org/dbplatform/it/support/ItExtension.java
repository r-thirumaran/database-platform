package org.dbplatform.it.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Wires every scenario class to the shared {@link Stack} (started once, stopped when the whole run ends, also on
 * failure, through the root store's CloseableResource) and records PASS/FAIL/PARTIAL per test in {@link Results}.
 */
public final class ItExtension implements BeforeAllCallback, BeforeEachCallback, TestWatcher {

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL);
        store.getOrComputeIfAbsent("dbp-it-results", k -> (ExtensionContext.Store.CloseableResource) () -> {
            Path out = Stack.workDir().resolve("results.md");
            Results.write(out);
            System.out.println("\n==== integration test results (" + out + ") ====\n" + Results.markdown());
        });
        store.getOrComputeIfAbsent("dbp-it-stack", k -> Stack.create(), Stack.class);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Results.begin();
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        Results.end(scenario(context), context.getDisplayName(), "PASS", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Results.end(scenario(context), context.getDisplayName(), "FAIL", String.valueOf(cause));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        Results.end(scenario(context), context.getDisplayName(), "SKIPPED", cause == null ? null : cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        Results.end(scenario(context), context.getDisplayName(), "SKIPPED", reason.orElse(null));
    }

    private static String scenario(ExtensionContext context) {
        return context.getParent().map(ExtensionContext::getDisplayName).orElse("?");
    }
}
