package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_SAVEPOINT (0x23): {@code string name} ({@code null} = unnamed), answered by SAVEPOINT_SET.
 *
 * @param name savepoint name or {@code null} for an unnamed savepoint
 */
public record SetSavepoint(String name) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_SAVEPOINT;
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
    public static SetSavepoint decode(ProtocolInput in) throws ProtocolException {
        return new SetSavepoint(in.readString());
    }
}
