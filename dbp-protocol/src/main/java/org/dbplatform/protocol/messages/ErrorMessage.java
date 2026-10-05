package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;

/**
 * ERROR (0x41): {@code string sqlState (nullable), i32 vendorCode, string message, bool fatal}.
 *
 * <p>Named {@code ErrorMessage} rather than {@code Error} to avoid clashing with {@link java.lang.Error}.</p>
 *
 * <p>Gateway-originated SQLStates (section 5): {@code 08001} cannot reach database / pool exhausted,
 * {@code 08004} not authorised / invalid api key / unsupported protocol version, {@code 08006} connection failure
 * (fatal), {@code 0A000} feature not supported, {@code 07005} unexpected result shape, {@code HY000} general gateway
 * error, {@code HY008} cancelled / timed out, {@code 42000} rejected by policy.</p>
 *
 * @param sqlState   SQLState or {@code null}
 * @param vendorCode vendor code (0 if none)
 * @param message    human-readable message
 * @param fatal      {@code true} if the gateway will close the socket; the driver marks the connection closed
 */
public record ErrorMessage(String sqlState, int vendorCode, String message, boolean fatal) implements Message {

    /** Cannot reach the physical database / pool exhausted. */
    public static final String STATE_CONNECTION_UNABLE = "08001";
    /** Application not authorised / invalid api key / unsupported protocol version. */
    public static final String STATE_REJECTED = "08004";
    /** Connection failure (always fatal). */
    public static final String STATE_CONNECTION_FAILURE = "08006";
    /** Feature not supported. */
    public static final String STATE_NOT_SUPPORTED = "0A000";
    /** Statement did not produce the expected result shape. */
    public static final String STATE_WRONG_RESULT_SHAPE = "07005";
    /** General gateway error (too many cursors, protocol violation, …). */
    public static final String STATE_GENERAL = "HY000";
    /** Statement cancelled / timed out. */
    public static final String STATE_TIMEOUT = "HY008";
    /** SQL rejected by a platform policy. */
    public static final String STATE_POLICY = "42000";

    /**
     * Creates a non-fatal gateway error.
     *
     * @param sqlState SQLState
     * @param message  message
     * @return the message
     */
    public static ErrorMessage of(String sqlState, String message) {
        return new ErrorMessage(sqlState, 0, message, false);
    }

    /**
     * Creates a fatal error (the gateway closes the socket afterwards).
     *
     * @param sqlState SQLState
     * @param message  message
     * @return the message
     */
    public static ErrorMessage fatal(String sqlState, String message) {
        return new ErrorMessage(sqlState, 0, message, true);
    }

    /**
     * Captures a physical {@link SQLException}.
     *
     * @param e     the exception
     * @param fatal whether the session is unusable afterwards
     * @return the message
     */
    public static ErrorMessage from(SQLException e, boolean fatal) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new ErrorMessage(e.getSQLState(), e.getErrorCode(), msg, fatal);
    }

    /**
     * Driver-side conversion to the {@link SQLException} subclass matching the SQLState class
     * ({@code 08} connection, {@code 0A} not supported, {@code 22} data, {@code 23} integrity, {@code 28}
     * authorisation, {@code 40} transaction rollback, {@code 42} syntax, {@code HY008} timeout). Fatal errors
     * always become {@link SQLNonTransientConnectionException}.
     *
     * @return a new exception
     */
    public SQLException toSqlException() {
        if (fatal) {
            return new SQLNonTransientConnectionException(message, sqlState, vendorCode);
        }
        if (sqlState == null || sqlState.length() < 2) {
            return new SQLException(message, sqlState, vendorCode);
        }
        if (STATE_TIMEOUT.equals(sqlState)) {
            return new SQLTimeoutException(message, sqlState, vendorCode);
        }
        return switch (sqlState.substring(0, 2)) {
            case "08" -> new SQLTransientConnectionException(message, sqlState, vendorCode);
            case "0A" -> new SQLFeatureNotSupportedException(message, sqlState, vendorCode);
            case "22" -> new SQLDataException(message, sqlState, vendorCode);
            case "23" -> new SQLIntegrityConstraintViolationException(message, sqlState, vendorCode);
            case "28" -> new SQLInvalidAuthorizationSpecException(message, sqlState, vendorCode);
            case "40" -> new SQLTransactionRollbackException(message, sqlState, vendorCode);
            case "42" -> new SQLSyntaxErrorException(message, sqlState, vendorCode);
            default -> new SQLException(message, sqlState, vendorCode);
        };
    }

    @Override
    public MessageType type() {
        return MessageType.ERROR;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(sqlState).writeI32(vendorCode).writeString(message).writeBool(fatal);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static ErrorMessage decode(ProtocolInput in) throws ProtocolException {
        String sqlState = in.readString();
        int vendorCode = in.readI32();
        String message = in.readString();
        boolean fatal = in.readBool();
        return new ErrorMessage(sqlState, vendorCode, message, fatal);
    }
}
