package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.List;

/**
 * GENERATED_KEYS (0x4A): {@code i32 columnCount, columnCount × ColumnMeta, i32 rowCount, rowCount × (columnCount ×
 * Value)}; the complete {@code Statement.getGeneratedKeys()} result set, sent before EXECUTE_DONE.
 *
 * @param columns column metadata, never {@code null}
 * @param rows    key rows, each with {@code columns.size()} cells; never {@code null}
 */
public record GeneratedKeys(List<ColumnMeta> columns, List<List<Object>> rows) implements Message {

    /**
     * Normalises and validates row widths.
     *
     * @param columns {@code null} = empty
     * @param rows    {@code null} = empty
     */
    public GeneratedKeys {
        columns = Codec.copyList(columns);
        for (ColumnMeta c : columns) {
            if (c == null) {
                throw new IllegalArgumentException("column metadata must not be null");
            }
        }
        rows = Codec.copyRows(rows);
        Codec.checkRowWidth(rows, columns.size());
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
        return MessageType.GENERATED_KEYS;
    }

    @Override
    public void encode(ProtocolOutput out) {
        Codec.writeColumns(out, columns);
        Codec.writeRows(out, rows);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static GeneratedKeys decode(ProtocolInput in) throws ProtocolException {
        List<ColumnMeta> columns = Codec.readColumns(in);
        List<List<Object>> rows = Codec.readRows(in, columns.size());
        return new GeneratedKeys(columns, rows);
    }
}
