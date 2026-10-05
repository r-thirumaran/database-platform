package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * CLOSE_STATEMENT (0x14): {@code i32 statementId}, answered by OK.
 *
 * @param statementId registered statement to close
 */
public record CloseStatement(int statementId) implements Message {

    @Override
    public MessageType type() {
        return MessageType.CLOSE_STATEMENT;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(statementId);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static CloseStatement decode(ProtocolInput in) throws ProtocolException {
        return new CloseStatement(in.readI32());
    }
}
