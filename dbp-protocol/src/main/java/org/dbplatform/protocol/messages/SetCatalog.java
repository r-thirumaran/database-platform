package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * SET_CATALOG (0x28): {@code string catalog}, answered by OK.
 *
 * @param catalog catalog name
 */
public record SetCatalog(String catalog) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SET_CATALOG;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(catalog);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static SetCatalog decode(ProtocolInput in) throws ProtocolException {
        return new SetCatalog(in.readString());
    }
}
