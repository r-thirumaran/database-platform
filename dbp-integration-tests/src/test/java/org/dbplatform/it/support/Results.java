package org.dbplatform.it.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects one row per test (scenario, test, PASS / FAIL / PARTIAL / SKIPPED, notes) for the validation report.
 * Tests add free-text findings with {@link #note(String, Object...)} and downgrade a green test to PARTIAL with
 * {@link #partial(String)} when part of the contract could not be exercised.
 */
public final class Results {
    public record Entry(String scenario, String test, String status, String notes) {
    }

    private static final List<Entry> ENTRIES = Collections.synchronizedList(new ArrayList<>());
    private static final ThreadLocal<List<String>> NOTES = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean> PARTIAL = ThreadLocal.withInitial(() -> false);

    private Results() {
    }

    public static void note(String fmt, Object... args) {
        String s = args.length == 0 ? fmt : String.format(fmt, args);
        NOTES.get().add(s);
    }

    public static void partial(String reason) {
        PARTIAL.set(true);
        NOTES.get().add("PARTIAL: " + reason);
    }

    static void begin() {
        NOTES.get().clear();
        PARTIAL.set(false);
    }

    static void end(String scenario, String test, String status, String failure) {
        List<String> notes = new ArrayList<>(NOTES.get());
        String st = status;
        if ("PASS".equals(status) && PARTIAL.get()) {
            st = "PARTIAL";
        }
        if (failure != null && !failure.isBlank()) {
            notes.add("FAILURE: " + failure.lines().findFirst().orElse(failure));
        }
        ENTRIES.add(new Entry(scenario, test, st, String.join("; ", notes)));
        NOTES.get().clear();
        PARTIAL.set(false);
    }

    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    public static String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Scenario | Test | Result | Notes |\n|---|---|---|---|\n");
        for (Entry e : entries()) {
            sb.append("| ").append(cell(e.scenario())).append(" | ").append(cell(e.test())).append(" | ").append(e.status())
                    .append(" | ").append(cell(e.notes())).append(" |\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    static void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, markdown(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("cannot write results: " + e);
        }
    }
}
