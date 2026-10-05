package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolOutput;

/**
 * Common type of all wire messages (section 4 of the specification). Every implementation is an immutable
 * record with a {@code static decode(ProtocolInput)} factory (ROWS additionally needs the column count,
 * see {@link Rows#decode(org.dbplatform.protocol.ProtocolInput, int)}); {@link Messages#decode(Frame)} dispatches
 * on the frame type.
 */
public sealed interface Message permits
        Hello, Ping, Close,
        Prepare, Execute, Fetch, CloseCursor, CloseStatement, ExecuteBatch,
        SetAutoCommit, Commit, Rollback, SetSavepoint, ReleaseSavepoint, SetTransactionIsolation,
        SetReadOnly, SetSchema, SetCatalog, SetClientInfo, SetNetworkTimeout,
        Metadata,
        Ok, ErrorMessage, HelloOk, Pong, Prepared, ResultSetHeader, Rows, UpdateCount, OutParams,
        ExecuteDone, GeneratedKeys, BatchResult, SavepointSet {

    /**
     * Returns the wire type of this message.
     *
     * @return the type
     */
    MessageType type();

    /**
     * Appends the payload of this message (without frame header) to {@code out}.
     *
     * @param out destination
     */
    void encode(ProtocolOutput out);

    /**
     * Encodes the payload of this message (without frame header).
     *
     * @return payload bytes
     */
    default byte[] encode() {
        ProtocolOutput out = new ProtocolOutput();
        encode(out);
        return out.toByteArray();
    }

    /**
     * Encodes this message into a frame.
     *
     * @return the frame
     */
    default Frame toFrame() {
        return new Frame(type(), encode());
    }
}
