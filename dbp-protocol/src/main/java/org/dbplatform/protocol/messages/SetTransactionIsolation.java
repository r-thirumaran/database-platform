package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_TRANSACTION_ISOLATION (0x25): {@code i32 level} ({@code java.sql.Connection.TRANSACTION_*}), answered by OK.
 *
 * @param level isolation level constant
 */
public record SetTransactionIsolation(int level) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_TRANSACTION_ISOLATION;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(level);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static SetTransactionIsolation decode(ProtocolInput in) throws ProtocolException {
        return new SetTransactionIsolation(in.readI32());
    }
}
