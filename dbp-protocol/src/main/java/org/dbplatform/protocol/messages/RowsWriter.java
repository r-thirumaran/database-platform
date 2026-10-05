package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolOutput;
import org.dbplatform.protocol.ValueTag;
import org.dbplatform.protocol.Values;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Streaming builder for a ROWS payload: rows (or single cells) are appended as they are produced and the
 * {@code rowCount} prefix is patched in when {@link #finish(boolean)} is called, so the gateway never has to
 * materialise a chunk of rows as Java objects.
 *
 * <pre>{@code
 * RowsWriter w = new RowsWriter(cursorId, tags.length);
 * while (w.rowCount() < fetchSize && rs.next()) {
 *     w.addRow(rs, tags);
 * }
 * boolean exhausted = w.rowCount() < fetchSize;   // rs.next() returned false
 * frameWriter.writeFrame(w.toFrame(exhausted));
 * }</pre>
 *
 * <p>Instances are single-use and not thread-safe.</p>
 */
public final class RowsWriter {

    private final ProtocolOutput out;
    private final int cursorId;
    private final int columnCount;
    private final int rowCountPosition;
    private int rowCount;
    private int cellIndex;
    private boolean finished;

    /**
     * Starts a ROWS payload.
     *
     * @param cursorId    cursor id
     * @param columnCount number of cells per row, {@code >= 0}
     */
    public RowsWriter(int cursorId, int columnCount) {
        this(cursorId, columnCount, 4096);
    }

    /**
     * Starts a ROWS payload with an initial buffer capacity hint.
     *
     * @param cursorId        cursor id
     * @param columnCount     number of cells per row, {@code >= 0}
     * @param initialCapacity buffer capacity hint in bytes
     */
    public RowsWriter(int cursorId, int columnCount, int initialCapacity) {
        if (columnCount < 0) {
            throw new IllegalArgumentException("columnCount must be >= 0");
        }
        this.cursorId = cursorId;
        this.columnCount = columnCount;
        this.out = new ProtocolOutput(initialCapacity);
        out.writeI32(cursorId);
        this.rowCountPosition = out.reserveI32();
    }

    /**
     * Returns the cursor id.
     *
     * @return cursor id
     */
    public int cursorId() {
        return cursorId;
    }

    /**
     * Returns the number of cells per row.
     *
     * @return column count
     */
    public int columnCount() {
        return columnCount;
    }

    /**
     * Returns the number of complete rows appended so far.
     *
     * @return row count
     */
    public int rowCount() {
        return rowCount;
    }

    /**
     * Returns the current payload size in bytes (useful to cap a chunk below the maximum frame size).
     *
     * @return bytes written so far
     */
    public int sizeBytes() {
        return out.size();
    }

    /**
     * Appends one cell of the current row; a row completes automatically after {@code columnCount} cells.
     *
     * @param value Java object or {@code null}, encoded with {@link Values#encode}
     * @return this
     */
    public RowsWriter addCell(Object value) {
        checkOpen();
        if (columnCount == 0) {
            throw new IllegalStateException("zero-column rows have no cells; use addEmptyRow()");
        }
        Values.encode(out, value);
        completeCell();
        return this;
    }

    /**
     * Appends one cell read from a physical result set with the getter matching {@code tag}
     * (see {@link Values#encodeColumn}).
     *
     * @param rs     result set positioned on a row
     * @param column 1-based column index
     * @param tag    the column's tag
     * @return this
     * @throws SQLException from the physical driver
     */
    public RowsWriter addCell(ResultSet rs, int column, ValueTag tag) throws SQLException {
        checkOpen();
        if (columnCount == 0) {
            throw new IllegalStateException("zero-column rows have no cells; use addEmptyRow()");
        }
        Values.encodeColumn(out, rs, column, tag);
        completeCell();
        return this;
    }

    /**
     * Appends a complete row.
     *
     * @param cells exactly {@code columnCount} values
     * @return this
     */
    public RowsWriter addRow(Object... cells) {
        checkRowStart();
        if (cells.length != columnCount) {
            throw new IllegalArgumentException("row has " + cells.length + " cells, expected " + columnCount);
        }
        for (Object c : cells) {
            Values.encode(out, c);
        }
        rowCount++;
        return this;
    }

    /**
     * Appends a complete row.
     *
     * @param cells exactly {@code columnCount} values
     * @return this
     */
    public RowsWriter addRow(List<?> cells) {
        checkRowStart();
        if (cells.size() != columnCount) {
            throw new IllegalArgumentException("row has " + cells.size() + " cells, expected " + columnCount);
        }
        for (Object c : cells) {
            Values.encode(out, c);
        }
        rowCount++;
        return this;
    }

    /**
     * Appends the current row of a physical result set, reading column {@code i + 1} with {@code tags[i]}.
     *
     * @param rs   result set positioned on a row
     * @param tags one tag per column, as chosen by {@link Values#tagForJdbcType}
     * @return this
     * @throws SQLException from the physical driver
     */
    public RowsWriter addRow(ResultSet rs, ValueTag[] tags) throws SQLException {
        checkRowStart();
        if (tags.length != columnCount) {
            throw new IllegalArgumentException("tags has " + tags.length + " entries, expected " + columnCount);
        }
        for (int i = 0; i < tags.length; i++) {
            Values.encodeColumn(out, rs, i + 1, tags[i]);
        }
        rowCount++;
        return this;
    }

    /**
     * Appends a row to a zero-column result.
     *
     * @return this
     */
    public RowsWriter addEmptyRow() {
        checkRowStart();
        if (columnCount != 0) {
            throw new IllegalStateException("result has " + columnCount + " columns");
        }
        rowCount++;
        return this;
    }

    /**
     * Completes the payload. The writer cannot be used afterwards.
     *
     * @param last {@code true} if the cursor is exhausted
     * @return the ROWS payload
     * @throws IllegalStateException if a row is only partially written or the writer was already finished
     */
    public byte[] finish(boolean last) {
        checkOpen();
        if (cellIndex != 0) {
            throw new IllegalStateException("row incomplete: " + cellIndex + " of " + columnCount + " cells written");
        }
        finished = true;
        out.putI32At(rowCountPosition, rowCount);
        out.writeBool(last);
        return out.toByteArray();
    }

    /**
     * Completes the payload and wraps it in a frame.
     *
     * @param last {@code true} if the cursor is exhausted
     * @return the ROWS frame
     */
    public Frame toFrame(boolean last) {
        return new Frame(MessageType.ROWS, finish(last));
    }

    /**
     * Completes the payload and writes it as a frame.
     *
     * @param writer destination
     * @param last   {@code true} if the cursor is exhausted
     * @throws IOException from the writer
     */
    public void writeTo(FrameWriter writer, boolean last) throws IOException {
        writer.writeFrame(MessageType.ROWS, finish(last));
    }

    private void completeCell() {
        if (++cellIndex == columnCount) {
            cellIndex = 0;
            rowCount++;
        }
    }

    private void checkRowStart() {
        checkOpen();
        if (cellIndex != 0) {
            throw new IllegalStateException("previous row incomplete: " + cellIndex + " of " + columnCount + " cells written");
        }
    }

    private void checkOpen() {
        if (finished) {
            throw new IllegalStateException("RowsWriter already finished");
        }
    }
}
