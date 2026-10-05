package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * PREPARED (0x44): {@code i32 statementId, i32 parameterCount (-1 = unknown)}, answer to PREPARE.
 *
 * @param statementId    id to use in EXECUTE / EXECUTE_BATCH / CLOSE_STATEMENT
 * @param parameterCount number of parameter markers, or {@link ProtocolConstants#UNKNOWN_PARAMETER_COUNT}
 */
public record Prepared(int statementId, int parameterCount) implements Message {

    @Override
    public MessageType type() {
        return MessageType.PREPARED;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(statementId).writeI32(parameterCount);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static Prepared decode(ProtocolInput in) throws ProtocolException {
        int statementId = in.readI32();
        int parameterCount = in.readI32();
        return new Prepared(statementId, parameterCount);
    }
}
