package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * FETCH (0x12): {@code i32 cursorId, i32 maxRows}, answered by one ROWS frame.
 *
 * @param cursorId open cursor
 * @param maxRows  maximum number of rows wanted; {@code <= 0} = gateway default fetch size
 */
public record Fetch(int cursorId, int maxRows) implements Message {

    @Override
    public MessageType type() {
        return MessageType.FETCH;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(cursorId).writeI32(maxRows);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static Fetch decode(ProtocolInput in) throws ProtocolException {
        int cursorId = in.readI32();
        int maxRows = in.readI32();
        return new Fetch(cursorId, maxRows);
    }
}
