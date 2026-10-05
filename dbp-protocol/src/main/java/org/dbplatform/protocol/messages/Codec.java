package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;
import org.dbplatform.protocol.Warning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private helpers shared by the message records: null-tolerant defensive copies and the
 * repeated {@code count × X} structures.
 */
final class Codec {

    /** Minimum encoded size of a {@code ColumnMeta} (7 null strings, 4 ints, 1 byte, 6 bools). */
    static final int MIN_COLUMN_META_BYTES = 7 * 4 + 4 * 4 + 1 + 6;
    /** Minimum encoded size of a {@code Warning} (2 null strings + i32). */
    static final int MIN_WARNING_BYTES = 4 + 4 + 4;
    /** Upper bound accepted for the row count of a zero-column result (cannot be size-validated). */
    static final int MAX_ZERO_COLUMN_ROWS = 1_000_000;

    private Codec() {
    }

    /** Unmodifiable copy that, unlike {@link List#copyOf}, tolerates {@code null} elements. */
    static <T> List<T> copyList(List<? extends T> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** Unmodifiable deep copy of a row list (each row copied, {@code null} cells kept). */
    static List<List<Object>> copyRows(List<? extends List<?>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (List<?> row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("row must not be null");
            }
            out.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return Collections.unmodifiableList(out);
    }

    /** Unmodifiable insertion-ordered copy; {@code null} → empty. */
    static Map<String, String> copyMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** Checks that every row has {@code columnCount} cells. */
    static void checkRowWidth(List<List<Object>> rows, int columnCount) {
        for (List<Object> row : rows) {
            if (row.size() != columnCount) {
                throw new IllegalArgumentException("row has " + row.size() + " cells but " + columnCount
                        + " columns were declared");
            }
        }
    }

    static void writeWarnings(ProtocolOutput out, List<Warning> warnings) {
        out.writeI32(warnings.size());
        for (Warning w : warnings) {
            w.encode(out);
        }
    }

    static List<Warning> readWarnings(ProtocolInput in) throws ProtocolException {
        int n = in.readCount("Warning", MIN_WARNING_BYTES);
        List<Warning> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            l.add(Warning.decode(in));
        }
        return l;
    }

    static void writeColumns(ProtocolOutput out, List<ColumnMeta> columns) {
        out.writeI32(columns.size());
        for (ColumnMeta c : columns) {
            c.encode(out);
        }
    }

    static List<ColumnMeta> readColumns(ProtocolInput in) throws ProtocolException {
        int n = in.readCount("ColumnMeta", MIN_COLUMN_META_BYTES);
        List<ColumnMeta> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            l.add(ColumnMeta.decode(in));
        }
        return l;
    }

    /** Writes {@code i32 rowCount} followed by the cells of every row. */
    static void writeRows(ProtocolOutput out, List<List<Object>> rows) {
        out.writeI32(rows.size());
        for (List<Object> row : rows) {
            out.writeRow(row);
        }
    }

    /** Reads {@code i32 rowCount} followed by {@code rowCount × columnCount} cells. */
    static List<List<Object>> readRows(ProtocolInput in, int columnCount) throws ProtocolException {
        if (columnCount < 0) {
            throw new ProtocolException("negative column count " + columnCount);
        }
        int rowCount;
        if (columnCount == 0) {
            // zero-column rows occupy no bytes, so the count cannot be validated against the payload size
            rowCount = in.readI32();
            if (rowCount < 0 || rowCount > MAX_ZERO_COLUMN_ROWS) {
                throw new ProtocolException("implausible row count " + rowCount + " for a zero-column result");
            }
        } else {
            rowCount = in.readCount("row", columnCount);
        }
        List<List<Object>> rows = new ArrayList<>(Math.min(rowCount, 4096));
        for (int i = 0; i < rowCount; i++) {
            rows.add(in.readRow(columnCount));
        }
        return rows;
    }
}
