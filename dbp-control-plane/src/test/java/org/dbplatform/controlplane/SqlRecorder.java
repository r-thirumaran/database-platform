package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate {@link StatementInspector} registered for the whole test context
 * ({@code hibernate.session_factory.statement_inspector} in application-test.yml). It records every SQL statement
 * Hibernate prepares while a {@link Capture} is open, so tests can assert which tables were written.
 * The capture is global (all threads), which is what the concurrency tests need.
 */
public class SqlRecorder implements StatementInspector {
    private static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();
    private static volatile boolean active;

    @Override
    public String inspect(String sql) {
        if (active && sql != null) STATEMENTS.add(sql);
        return sql;
    }

    /** Starts recording; close it (try-with-resources) to stop. Captures must not overlap. */
    public static Capture start() {
        STATEMENTS.clear();
        active = true;
        return new Capture();
    }

    public static final class Capture implements AutoCloseable {
        public List<String> statements() { return new ArrayList<>(STATEMENTS); }

        /** All recorded {@code update <table> set ...} statements of the given table. */
        public List<String> updatesOf(String table) {
            Pattern p = Pattern.compile("^\\s*update\\s+\"?" + Pattern.quote(table) + "\"?\\s+set\\b", Pattern.CASE_INSENSITIVE);
            return statements().stream().filter(s -> p.matcher(s.toLowerCase(Locale.ROOT)).find()).toList();
        }

        /** Every recorded update statement, whatever the table. */
        public List<String> updates() {
            return statements().stream().filter(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("update ")).toList();
        }

        /** Fails (listing every recorded statement) when any of the given tables was updated. */
        public void assertNoUpdates(String what, String... tables) {
            for (String table : tables) {
                assertThat(updatesOf(table)).as("%s must not issue 'update %s' (all statements: %s)", what, table, statements()).isEmpty();
            }
        }

        @Override public void close() { active = false; }
    }
}
