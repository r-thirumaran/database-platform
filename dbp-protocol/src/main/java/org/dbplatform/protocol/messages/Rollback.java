package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * ROLLBACK (0x22): {@code string savepointName} ({@code null} = roll back the whole transaction), answered by OK.
 *
 * @param savepointName savepoint to roll back to, or {@code null} for a full rollback
 */
public record Rollback(String savepointName) implements Message {

    /** Roll back the whole transaction. */
    public static final Rollback FULL = new Rollback(null);

    /**
     * Returns whether this is a full rollback (no savepoint).
     *
     * @return {@code true} if {@link #savepointName()} is {@code null}
     */
    public boolean isFull() {
        return savepointName == null;
    }

    @Override
    public MessageType type() {
        return MessageType.ROLLBACK;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(savepointName);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static Rollback decode(ProtocolInput in) throws ProtocolException {
        return new Rollback(in.readString());
    }
}
