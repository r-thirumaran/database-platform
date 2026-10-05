package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;
import org.dbplatform.protocol.Warning;

import java.util.List;

/**
 * EXECUTE_DONE (0x49): {@code i32 warningCount, warningCount × Warning}; terminal frame of an EXECUTE or
 * METADATA exchange.
 *
 * @param warnings JDBC warnings raised by the physical statement, never {@code null}
 */
public record ExecuteDone(List<Warning> warnings) implements Message {

    /** Completion without warnings. */
    public static final ExecuteDone NO_WARNINGS = new ExecuteDone(List.of());

    /**
     * Normalises the list.
     *
     * @param warnings {@code null} = empty
     */
    public ExecuteDone {
        warnings = Codec.copyList(warnings);
        for (Warning w : warnings) {
            if (w == null) {
                throw new IllegalArgumentException("warning must not be null");
            }
        }
    }

    @Override
    public MessageType type() {
        return MessageType.EXECUTE_DONE;
    }

    @Override
    public void encode(ProtocolOutput out) {
        Codec.writeWarnings(out, warnings);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static ExecuteDone decode(ProtocolInput in) throws ProtocolException {
        return new ExecuteDone(Codec.readWarnings(in));
    }
}
