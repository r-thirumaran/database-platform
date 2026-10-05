package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_SCHEMA (0x27): {@code string schema}, answered by OK.
 *
 * @param schema schema name
 */
public record SetSchema(String schema) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_SCHEMA;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(schema);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static SetSchema decode(ProtocolInput in) throws ProtocolException {
        return new SetSchema(in.readString());
    }
}
