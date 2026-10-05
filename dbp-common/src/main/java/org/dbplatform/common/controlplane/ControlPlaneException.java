package org.dbplatform.common.controlplane;

/**
 * Failure talking to the control plane. {@link #status()} is the HTTP status (0 for transport errors such
 * as connection refused or timeout). {@link #errorCode()} and {@link #path()} mirror the control plane's
 * error document {@code { "status", "error", "message", "path" }} when one was returned.
 */
public class ControlPlaneException extends RuntimeException {

    private final int status;
    private final String errorCode;
    private final String path;

    public ControlPlaneException(String message, int status, String errorCode, String path) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.path = path;
    }

    public ControlPlaneException(String message, String path, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.errorCode = "TRANSPORT";
        this.path = path;
    }

    /** HTTP status, or 0 when no response was received. */
    public int status() {
        return status;
    }

    /** Control plane error code such as {@code NOT_FOUND}, or {@code TRANSPORT}. */
    public String errorCode() {
        return errorCode;
    }

    public String path() {
        return path;
    }

    public boolean isTransport() {
        return status == 0;
    }

    public boolean isUnauthorized() {
        return status == 401;
    }

    public boolean isForbidden() {
        return status == 403;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /** True when retrying later could succeed (transport error, 408, 429, 5xx). */
    public boolean isRetryable() {
        return status == 0 || status == 408 || status == 429 || status >= 500;
    }
}
