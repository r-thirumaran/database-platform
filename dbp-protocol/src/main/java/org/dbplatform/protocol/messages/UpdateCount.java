package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * UPDATE_COUNT (0x47): {@code i64 count}, one result item of an EXECUTE response.
 *
 * @param count number of affected rows
 */
public record UpdateCount(long count) implements Message {

    @Override
    public MessageType type() {
        return MessageType.UPDATE_COUNT;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI64(count);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static UpdateCount decode(ProtocolInput in) throws ProtocolException {
        return new UpdateCount(in.readI64());
    }
}
