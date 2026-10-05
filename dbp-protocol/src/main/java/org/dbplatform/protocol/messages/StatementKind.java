package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.ProtocolException;

/**
 * The {@code kind} byte of PREPARE, EXECUTE and EXECUTE_BATCH: which JDBC statement flavour the client used.
 */
public enum StatementKind {
    /** {@code java.sql.Statement}. */
    STATEMENT(0),
    /** {@code java.sql.PreparedStatement}. */
    PREPARED(1),
    /** {@code java.sql.CallableStatement}. */
    CALLABLE(2);

    private final int code;

    StatementKind(int code) {
        this.code = code;
    }

    /**
     * Returns the wire code.
     *
     * @return {@code 0..2}
     */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire code.
     *
     * @param code the byte read
     * @return the kind
     * @throws ProtocolException if unknown
     */
    public static StatementKind fromCode(int code) throws ProtocolException {
        return switch (code) {
            case 0 -> STATEMENT;
            case 1 -> PREPARED;
            case 2 -> CALLABLE;
            default -> throw new ProtocolException("unknown statement kind " + code);
        };
    }
}
