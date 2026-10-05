package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * COMMIT (0x21), answered by OK. Empty payload.
 */
public record Commit() implements Message {

    /** Shared instance (the record carries no state). */
    public static final Commit INSTANCE = new Commit();

    @Override
    public MessageType type() {
        return MessageType.COMMIT;
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
    public static Commit decode(ProtocolInput in) throws ProtocolException {
        return INSTANCE;
    }
}
