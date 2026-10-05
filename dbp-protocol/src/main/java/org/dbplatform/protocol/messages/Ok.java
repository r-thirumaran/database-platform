package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * OK (0x40): generic success response. Empty payload.
 */
public record Ok() implements Message {

    /** Shared instance (the record carries no state). */
    public static final Ok INSTANCE = new Ok();

    @Override
    public MessageType type() {
        return MessageType.OK;
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
    public static Ok decode(ProtocolInput in) throws ProtocolException {
        return INSTANCE;
    }
}
