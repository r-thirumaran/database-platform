package org.dbplatform.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * One wire frame: a message type and its raw payload (without the length prefix and type byte).
 *
 * @param type    the message type
 * @param payload the payload bytes, never {@code null} (empty for payload-less messages)
 */
public record Frame(MessageType type, byte[] payload) {

    private static final byte[] EMPTY = new byte[0];

    /**
     * Creates a frame.
     *
     * @param type    the message type
     * @param payload the payload bytes; {@code null} is treated as empty
     */
    public Frame {
        Objects.requireNonNull(type, "type");
        payload = payload == null ? EMPTY : payload;
    }

    /**
     * Creates a frame with an empty payload.
     *
     * @param type the message type
     * @return the frame
     */
    public static Frame empty(MessageType type) {
        return new Frame(type, EMPTY);
    }

    /**
     * Returns the value of the {@code u32 length} field for this frame (type byte plus payload).
     *
     * @return the frame length as it appears on the wire
     */
    public long wireLength() {
        return 1L + payload.length;
    }

    /**
     * Returns a reader positioned at the start of the payload.
     *
     * @return a new {@link ProtocolInput}
     */
    public ProtocolInput input() {
        return new ProtocolInput(payload);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frame f && f.type == type && Arrays.equals(f.payload, payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Frame[" + type + ", " + payload.length + " bytes]";
    }
}
