package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * CLOSE (0x03): closes the session, answered by OK. Empty payload.
 */
public record Close() implements Message {

    /** Shared instance (the record carries no state). */
    public static final Close INSTANCE = new Close();

    @Override
    public MessageType type() {
        return MessageType.CLOSE;
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
    public static Close decode(ProtocolInput in) throws ProtocolException {
        return INSTANCE;
    }
}
