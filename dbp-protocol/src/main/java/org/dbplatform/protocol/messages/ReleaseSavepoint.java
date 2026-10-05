package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * RELEASE_SAVEPOINT (0x24): {@code string name}, answered by OK.
 *
 * @param name savepoint name (as returned in SAVEPOINT_SET)
 */
public record ReleaseSavepoint(String name) implements Message {

    @Override
    public MessageType type() {
        return MessageType.RELEASE_SAVEPOINT;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(name);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static ReleaseSavepoint decode(ProtocolInput in) throws ProtocolException {
        return new ReleaseSavepoint(in.readString());
    }
}
