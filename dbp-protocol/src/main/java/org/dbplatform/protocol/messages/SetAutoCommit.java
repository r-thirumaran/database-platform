package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_AUTOCOMMIT (0x20): {@code bool}, answered by OK. {@code false} marks the session transactional; {@code true} while a transaction is open commits it.
 *
 * @param autoCommit new auto-commit mode
 */
public record SetAutoCommit(boolean autoCommit) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_AUTOCOMMIT;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeBool(autoCommit);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static SetAutoCommit decode(ProtocolInput in) throws ProtocolException {
        return new SetAutoCommit(in.readBool());
    }
}
