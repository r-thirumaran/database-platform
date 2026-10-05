package org.dbplatform.gateway.control;

/**
 * Raised when a HELLO cannot be accepted. {@link #sqlState()} is {@code 08004} for authentication/authorisation
 * failures and {@code 08001} when the control plane or the physical database cannot be reached.
 */
public final class AuthException extends Exception {

    public static final String REJECTED = "08004";
    public static final String UNREACHABLE = "08001";

    private final String sqlState;

    public AuthException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState;
    }

    public AuthException(String sqlState, String message, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }

    public static AuthException rejected(String message) {
        return new AuthException(REJECTED, message);
    }

    public static AuthException unreachable(String message, Throwable cause) {
        return new AuthException(UNREACHABLE, message, cause);
    }
}
