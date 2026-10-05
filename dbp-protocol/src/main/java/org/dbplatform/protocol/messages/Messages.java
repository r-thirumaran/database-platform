package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;

import java.io.IOException;

/**
 * Dispatcher from a {@link Frame} to the matching {@link Message} record, plus stream convenience methods.
 *
 * <p>Trailing payload bytes are ignored, as required by section 6 of the specification ("readers must
 * never rely on trailing garbage"); call {@link ProtocolInput#expectEnd()} yourself when strictness is wanted.</p>
 */
public final class Messages {

    private Messages() {
    }

    /**
     * Decodes any frame except ROWS (which needs the column count of its RESULT_SET_HEADER).
     *
     * @param frame the frame
     * @return the message
     * @throws ProtocolException if the payload is malformed or the frame is a ROWS frame
     */
    public static Message decode(Frame frame) throws ProtocolException {
        return decode(frame, -1);
    }

    /**
     * Decodes any frame.
     *
     * @param frame       the frame
     * @param columnCount column count of the current result set, used for ROWS frames; ignored otherwise
     *                    (pass {@code -1} if no result set is open)
     * @return the message
     * @throws ProtocolException if the payload is malformed, or a ROWS frame arrives with {@code columnCount < 0}
     */
    public static Message decode(Frame frame, int columnCount) throws ProtocolException {
        ProtocolInput in = frame.input();
        return switch (frame.type()) {
            case HELLO -> Hello.decode(in);
            case PING -> Ping.decode(in);
            case CLOSE -> Close.decode(in);
            case PREPARE -> Prepare.decode(in);
            case EXECUTE -> Execute.decode(in);
            case FETCH -> Fetch.decode(in);
            case CLOSE_CURSOR -> CloseCursor.decode(in);
            case CLOSE_STATEMENT -> CloseStatement.decode(in);
            case EXECUTE_BATCH -> ExecuteBatch.decode(in);
            case SET_AUTOCOMMIT -> SetAutoCommit.decode(in);
            case COMMIT -> Commit.decode(in);
            case ROLLBACK -> Rollback.decode(in);
            case SET_SAVEPOINT -> SetSavepoint.decode(in);
            case RELEASE_SAVEPOINT -> ReleaseSavepoint.decode(in);
            case SET_TRANSACTION_ISOLATION -> SetTransactionIsolation.decode(in);
            case SET_READ_ONLY -> SetReadOnly.decode(in);
            case SET_SCHEMA -> SetSchema.decode(in);
            case SET_CATALOG -> SetCatalog.decode(in);
            case SET_CLIENT_INFO -> SetClientInfo.decode(in);
            case SET_NETWORK_TIMEOUT -> SetNetworkTimeout.decode(in);
            case METADATA -> Metadata.decode(in);
            case OK -> Ok.decode(in);
            case ERROR -> ErrorMessage.decode(in);
            case HELLO_OK -> HelloOk.decode(in);
            case PONG -> Pong.decode(in);
            case PREPARED -> Prepared.decode(in);
            case RESULT_SET_HEADER -> ResultSetHeader.decode(in);
            case ROWS -> {
                if (columnCount < 0) {
                    throw new ProtocolException("ROWS frame needs the column count of its RESULT_SET_HEADER;"
                            + " use Messages.decode(frame, columnCount)");
                }
                yield Rows.decode(in, columnCount);
            }
            case UPDATE_COUNT -> UpdateCount.decode(in);
            case OUT_PARAMS -> OutParams.decode(in);
            case EXECUTE_DONE -> ExecuteDone.decode(in);
            case GENERATED_KEYS -> GeneratedKeys.decode(in);
            case BATCH_RESULT -> BatchResult.decode(in);
            case SAVEPOINT_SET -> SavepointSet.decode(in);
        };
    }

    /**
     * Decodes a message from its type and payload.
     *
     * @param type    the type
     * @param payload the payload
     * @return the message
     * @throws ProtocolException see {@link #decode(Frame)}
     */
    public static Message decode(MessageType type, byte[] payload) throws ProtocolException {
        return decode(new Frame(type, payload));
    }

    /**
     * Reads and decodes the next frame; see {@link #decode(Frame)} for the ROWS restriction.
     *
     * @param reader the frame reader
     * @return the message
     * @throws IOException from the reader or on malformed input
     */
    public static Message read(FrameReader reader) throws IOException {
        return decode(reader.readFrame(), -1);
    }

    /**
     * Reads and decodes the next frame.
     *
     * @param reader      the frame reader
     * @param columnCount column count for ROWS frames, {@code -1} if none expected
     * @return the message
     * @throws IOException from the reader or on malformed input
     */
    public static Message read(FrameReader reader, int columnCount) throws IOException {
        return decode(reader.readFrame(), columnCount);
    }

    /**
     * Encodes and writes a message as one frame.
     *
     * @param writer  the frame writer
     * @param message the message
     * @throws IOException from the writer
     */
    public static void write(FrameWriter writer, Message message) throws IOException {
        writer.writeFrame(message.type(), message.encode());
    }
}
