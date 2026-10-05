package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * PING (0x02): liveness probe, answered by PONG. Empty payload.
 */
public record Ping() implements Message {

    /** Shared instance (the record carries no state). */
    public static final Ping INSTANCE = new Ping();

    @Override
    public MessageType type() {
        return MessageType.PING;
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
    public static Ping decode(ProtocolInput in) throws ProtocolException {
        return INSTANCE;
    }
}
