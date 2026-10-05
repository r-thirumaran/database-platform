package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * PONG (0x43): answer to PING. Empty payload.
 */
public record Pong() implements Message {

    /** Shared instance (the record carries no state). */
    public static final Pong INSTANCE = new Pong();

    @Override
    public MessageType type() {
        return MessageType.PONG;
    }

    @Override
    public void encode(ProtocolOutput out) {
        // no payload
    }

    /**
     * Decodes the (empty) payload.
     *
     * @param in source
     * @return {@link #INSTANCE}
     * @throws ProtocolException never; declared for uniformity with the other messages
     */
    public static Pong decode(ProtocolInput in) throws ProtocolException {
        return INSTANCE;
    }
}
