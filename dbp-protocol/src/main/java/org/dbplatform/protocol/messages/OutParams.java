package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OUT_PARAMS (0x48): {@code i32 count, count × (i32 index, Value value)}; the OUT / INOUT parameter values of a
 * CALLABLE execution, sent before GENERATED_KEYS / EXECUTE_DONE.
 *
 * @param entries parameter values in the order registered, never {@code null}
 */
public record OutParams(List<Entry> entries) implements Message {

    /**
     * One OUT parameter value.
     *
     * @param index 1-based parameter index
     * @param value decoded value (see {@link org.dbplatform.protocol.Values}) or {@code null}
     */
    public record Entry(int index, Object value) {
        /** Minimum encoded size: i32 index plus a one-byte NULL value. */
        static final int MIN_BYTES = 4 + 1;
    }

    /**
     * Normalises the list.
     *
     * @param entries {@code null} = empty
     */
    public OutParams {
        entries = Codec.copyList(entries);
        for (Entry e : entries) {
            if (e == null) {
                throw new IllegalArgumentException("entry must not be null");
            }
        }
    }

    /**
     * Returns the values keyed by parameter index.
     *
     * @return insertion-ordered mutable map
     */
    public Map<Integer, Object> asMap() {
        Map<Integer, Object> m = new LinkedHashMap<>();
        for (Entry e : entries) {
            m.put(e.index(), e.value());
        }
        return m;
    }

    @Override
    public MessageType type() {
        return MessageType.OUT_PARAMS;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(entries.size());
        for (Entry e : entries) {
            out.writeI32(e.index()).writeValue(e.value());
        }
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static OutParams decode(ProtocolInput in) throws ProtocolException {
        int n = in.readCount("OutParam value", Entry.MIN_BYTES);
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int index = in.readI32();
            Object value = in.readValue();
            entries.add(new Entry(index, value));
        }
        return new OutParams(entries);
    }
}
