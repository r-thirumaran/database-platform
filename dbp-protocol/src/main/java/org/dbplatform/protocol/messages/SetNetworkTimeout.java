package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_NETWORK_TIMEOUT (0x2A): {@code i32 millis}, answered by OK.
 *
 * @param millis timeout in milliseconds, 0 = none
 */
public record SetNetworkTimeout(int millis) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_NETWORK_TIMEOUT;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(millis);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static SetNetworkTimeout decode(ProtocolInput in) throws ProtocolException {
        return new SetNetworkTimeout(in.readI32());
    }
}
