package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * CLOSE_CURSOR (0x13): {@code i32 cursorId}, answered by OK.
 *
 * @param cursorId cursor to close
 */
public record CloseCursor(int cursorId) implements Message {

    @Override
    public MessageType type() {
        return MessageType.CLOSE_CURSOR;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(cursorId);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static CloseCursor decode(ProtocolInput in) throws ProtocolException {
        return new CloseCursor(in.readI32());
    }
}
