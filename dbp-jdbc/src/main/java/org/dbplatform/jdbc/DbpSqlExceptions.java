package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.ErrorMessage;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;

/**
 * Factory for the {@link SQLException}s raised by the driver. Every exception carries a SQLState.
 *
 * <ul>
 *   <li>{@code 08003} connection closed, {@code 08006} connection failure</li>
 *   <li>{@code 0A000} feature not supported</li>
 *   <li>{@code 07005} wrong result shape, {@code 07009} invalid descriptor index (column / parameter index)</li>
 *   <li>{@code 22003} numeric value out of range, {@code 22018} invalid character value for cast</li>
 *   <li>{@code 24000} invalid cursor state, {@code HY010} function sequence error (closed statement),
 *       {@code HY000} protocol violation</li>
 * </ul>
 */
final class DbpSqlExceptions {

    static final String STATE_CONNECTION_CLOSED = "08003";
    static final String STATE_CONNECTION_FAILURE = "08006";
    static final String STATE_NOT_SUPPORTED = "0A000";
    static final String STATE_WRONG_RESULT_SHAPE = "07005";
    static final String STATE_INVALID_INDEX = "07009";
    static final String STATE_NUMERIC_OUT_OF_RANGE = "22003";
    static final String STATE_INVALID_CAST = "22018";
    static final String STATE_INVALID_CURSOR_STATE = "24000";
    static final String STATE_SEQUENCE_ERROR = "HY010";
    static final String STATE_GENERAL = "HY000";
    static final String STATE_INVALID_ARGUMENT = "HY024";

    private DbpSqlExceptions() {
    }

    /**
     * Maps a gateway ERROR to an {@link SQLException} subclass. A fatal error marks the connection closed.
     */
    static SQLException fromError(ErrorMessage error, DbpConnection connection) {
        SQLException e = error.toSqlException();
        if (error.fatal() && connection != null) {
            connection.markClosed(e);
        }
        return e;
    }

    static SQLFeatureNotSupportedException notSupported(String feature) {
        return new SQLFeatureNotSupportedException(feature + " is not supported by the DBP JDBC driver",
                STATE_NOT_SUPPORTED);
    }

    static SQLNonTransientConnectionException connectionClosed() {
        return new SQLNonTransientConnectionException("connection is closed", STATE_CONNECTION_CLOSED);
    }

    static SQLException statementClosed() {
        return new SQLNonTransientException("statement is closed", STATE_SEQUENCE_ERROR);
    }

    static SQLException resultSetClosed() {
        return new SQLNonTransientException("result set is closed", STATE_INVALID_CURSOR_STATE);
    }

    static SQLException invalidCursorState(String message) {
        return new SQLNonTransientException(message, STATE_INVALID_CURSOR_STATE);
    }

    static SQLException invalidIndex(String message) {
        return new SQLNonTransientException(message, STATE_INVALID_INDEX);
    }

    static SQLException wrongResultShape(String message) {
        return new SQLNonTransientException(message, STATE_WRONG_RESULT_SHAPE);
    }

    static SQLException protocolViolation(String message) {
        return new SQLNonTransientException("protocol violation: " + message, STATE_GENERAL);
    }

    static SQLException invalidArgument(String message) {
        return new SQLNonTransientException(message, STATE_INVALID_ARGUMENT);
    }

    static SQLDataException outOfRange(Object value, String targetType) {
        return new SQLDataException("value " + value + " is out of range for " + targetType, STATE_NUMERIC_OUT_OF_RANGE);
    }

    static SQLDataException cannotConvert(Object value, String targetType) {
        String from = value == null ? "null" : value.getClass().getSimpleName();
        return new SQLDataException("cannot convert " + from + " value to " + targetType, STATE_INVALID_CAST);
    }

    static SQLDataException cannotConvert(Object value, String targetType, Throwable cause) {
        SQLDataException e = cannotConvert(value, targetType);
        e.initCause(cause);
        return e;
    }
}
