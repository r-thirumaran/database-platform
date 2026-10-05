package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_CLIENT_INFO (0x29): {@code string name, string value (nullable)}, answered by OK.
 *
 * @param name  client info property name (e.g. {@code ApplicationName})
 * @param value value or {@code null} to clear
 */
public record SetClientInfo(String name, String value) implements Message {

    /**
     * Validates the name.
     *
     * @param name  non-null name
     * @param value nullable value
     */
    public SetClientInfo {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
    }

    @Override
    public MessageType type() {
        return MessageType.SET_CLIENT_INFO;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(name).writeString(value);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed or the name is null
     */
    public static SetClientInfo decode(ProtocolInput in) throws ProtocolException {
        String name = in.readString();
        String value = in.readString();
        if (name == null) {
            throw new ProtocolException("SET_CLIENT_INFO name must not be null");
        }
        return new SetClientInfo(name, value);
    }
}
