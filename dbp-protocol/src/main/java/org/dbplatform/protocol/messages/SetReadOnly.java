package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_READ_ONLY (0x26): {@code bool}, answered by OK.
 *
 * @param readOnly read-only flag
 */
public record SetReadOnly(boolean readOnly) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_READ_ONLY;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeBool(readOnly);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static SetReadOnly decode(ProtocolInput in) throws ProtocolException {
        return new SetReadOnly(in.readBool());
    }
}
