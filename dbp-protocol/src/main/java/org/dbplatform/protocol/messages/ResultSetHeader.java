package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.List;

/**
 * RESULT_SET_HEADER (0x45): {@code i32 cursorId, i32 columnCount, columnCount × ColumnMeta}. Always immediately
 * followed by one ROWS frame; the receiver must remember {@link #columnCount()} to decode it.
 *
 * @param cursorId cursor id, unique per session
 * @param columns  column metadata, never {@code null}
 */
public record ResultSetHeader(int cursorId, List<ColumnMeta> columns) implements Message {

    /**
     * Normalises the column list.
     *
     * @param cursorId see record
     * @param columns  {@code null} = empty
     */
    public ResultSetHeader {
        columns = Codec.copyList(columns);
        for (ColumnMeta c : columns) {
            if (c == null) {
                throw new IllegalArgumentException("column metadata must not be null");
            }
        }
    }

    /**
     * Returns the number of columns.
     *
     * @return column count
     */
    public int columnCount() {
        return columns.size();
    }

    @Override
    public MessageType type() {
        return MessageType.RESULT_SET_HEADER;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(cursorId);
        Codec.writeColumns(out, columns);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static ResultSetHeader decode(ProtocolInput in) throws ProtocolException {
        int cursorId = in.readI32();
        List<ColumnMeta> columns = Codec.readColumns(in);
        return new ResultSetHeader(cursorId, columns);
    }
}
