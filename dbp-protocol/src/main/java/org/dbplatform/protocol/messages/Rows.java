package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.List;

/**
 * ROWS (0x46): {@code i32 cursorId, i32 rowCount, rowCount × (columnCount × Value), bool last}.
 *
 * <p>The column count is <em>not</em> on the wire: decoding needs it from the preceding RESULT_SET_HEADER
 * ({@link #decode(ProtocolInput, int)}). The gateway should build ROWS frames with {@link RowsWriter} so that
 * rows are appended as they are read from the physical result set instead of being materialised.</p>
 *
 * @param cursorId cursor the rows belong to
 * @param rows     the rows (each a list of {@code columnCount} cells, cells may be {@code null}); never {@code null}
 * @param last     {@code true} if the cursor is exhausted and has been closed by the server
 */
public record Rows(int cursorId, List<List<Object>> rows, boolean last) implements Message {

    /**
     * Normalises and validates that all rows have the same width.
     *
     * @param cursorId see record
     * @param rows     {@code null} = empty
     * @param last     see record
     */
    public Rows {
        rows = Codec.copyRows(rows);
        if (!rows.isEmpty()) {
            Codec.checkRowWidth(rows, rows.get(0).size());
        }
    }

    /**
     * Creates an empty, final ROWS frame (cursor exhausted without further rows).
     *
     * @param cursorId cursor id
     * @return the message
     */
    public static Rows endOf(int cursorId) {
        return new Rows(cursorId, List.of(), true);
    }

    /**
     * Returns the number of rows.
     *
     * @return row count
     */
    public int rowCount() {
        return rows.size();
    }

    @Override
    public MessageType type() {
        return MessageType.ROWS;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(cursorId);
        Codec.writeRows(out, rows);
        out.writeBool(last);
    }

    /**
     * Decodes the payload.
     *
     * @param in          source
     * @param columnCount column count from the RESULT_SET_HEADER of this cursor
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static Rows decode(ProtocolInput in, int columnCount) throws ProtocolException {
        int cursorId = in.readI32();
        List<List<Object>> rows = Codec.readRows(in, columnCount);
        boolean last = in.readBool();
        return new Rows(cursorId, rows, last);
    }
}
