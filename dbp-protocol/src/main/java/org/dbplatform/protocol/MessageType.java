package org.dbplatform.protocol;

/**
 * Message type codes of the DBP wire protocol v1 (section 4 of the specification).
 *
 * <p>Client → server types use {@code 0x01–0x3F}; server → client types use {@code 0x40–0x7F}.</p>
 */
public enum MessageType {

    // 4.1 session control
    /** Session handshake. */
    HELLO(0x01),
    /** Liveness probe. */
    PING(0x02),
    /** Close the session. */
    CLOSE(0x03),

    // 4.2 statements
    /** Register SQL for later execution. */
    PREPARE(0x10),
    /** Execute SQL or a registered statement. */
    EXECUTE(0x11),
    /** Fetch more rows from a cursor. */
    FETCH(0x12),
    /** Close a cursor. */
    CLOSE_CURSOR(0x13),
    /** Close a registered statement. */
    CLOSE_STATEMENT(0x14),
    /** Execute a batch. */
    EXECUTE_BATCH(0x15),

    // 4.3 transactions and session settings
    /** Set auto-commit. */
    SET_AUTOCOMMIT(0x20),
    /** Commit. */
    COMMIT(0x21),
    /** Roll back (optionally to a savepoint). */
    ROLLBACK(0x22),
    /** Create a savepoint. */
    SET_SAVEPOINT(0x23),
    /** Release a savepoint. */
    RELEASE_SAVEPOINT(0x24),
    /** Set the transaction isolation level. */
    SET_TRANSACTION_ISOLATION(0x25),
    /** Set the read-only flag. */
    SET_READ_ONLY(0x26),
    /** Set the current schema. */
    SET_SCHEMA(0x27),
    /** Set the current catalog. */
    SET_CATALOG(0x28),
    /** Set a client info property. */
    SET_CLIENT_INFO(0x29),
    /** Set the network timeout. */
    SET_NETWORK_TIMEOUT(0x2A),

    // 4.4 metadata
    /** Invoke a {@code DatabaseMetaData} method returning a result set. */
    METADATA(0x30),

    // 4.5 server -> client
    /** Generic success. */
    OK(0x40),
    /** Error. */
    ERROR(0x41),
    /** Handshake accepted. */
    HELLO_OK(0x42),
    /** Answer to PING. */
    PONG(0x43),
    /** Answer to PREPARE. */
    PREPARED(0x44),
    /** Result-set metadata, always followed by one ROWS frame. */
    RESULT_SET_HEADER(0x45),
    /** A chunk of rows. */
    ROWS(0x46),
    /** Update count result item. */
    UPDATE_COUNT(0x47),
    /** OUT parameter values of a callable execution. */
    OUT_PARAMS(0x48),
    /** Terminal frame of an EXECUTE / METADATA exchange. */
    EXECUTE_DONE(0x49),
    /** Generated keys of an execution. */
    GENERATED_KEYS(0x4A),
    /** Answer to EXECUTE_BATCH. */
    BATCH_RESULT(0x4B),
    /** Answer to SET_SAVEPOINT. */
    SAVEPOINT_SET(0x50);

    private static final MessageType[] BY_CODE = new MessageType[0x80];

    static {
        for (MessageType t : values()) {
            BY_CODE[t.code] = t;
        }
    }

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    /**
     * Returns the one-byte wire code of this message type.
     *
     * @return the code, {@code 0x01..0x7F}
     */
    public int code() {
        return code;
    }

    /**
     * Returns whether this type is sent by the server to the client ({@code 0x40..0x7F}).
     *
     * @return {@code true} for server → client types
     */
    public boolean isServerToClient() {
        return code >= 0x40;
    }

    /**
     * Resolves a wire code to a message type.
     *
     * @param code the code read from the frame header
     * @return the message type
     * @throws ProtocolException if the code is unknown
     */
    public static MessageType fromCode(int code) throws ProtocolException {
        MessageType t = (code >= 0 && code < BY_CODE.length) ? BY_CODE[code] : null;
        if (t == null) {
            throw new ProtocolException("unknown message type code 0x" + Integer.toHexString(code));
        }
        return t;
    }
}
