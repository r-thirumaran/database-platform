package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SAVEPOINT_SET (0x50): {@code string name}, answer to SET_SAVEPOINT. For unnamed savepoints the gateway assigns a name the client must use in ROLLBACK / RELEASE_SAVEPOINT.
 *
 * @param name assigned savepoint name
 */
public record SavepointSet(String name) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SAVEPOINT_SET;
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
    public static SavepointSet decode(ProtocolInput in) throws ProtocolException {
        return new SavepointSet(in.readString());
    }
}
