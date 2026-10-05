package org.dbplatform.controlplane.api.error;

import org.springframework.http.HttpStatus;

/** Base class of all API errors; rendered by {@link GlobalExceptionHandler} into the contract's error body. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() { return status; }
    public String getError() { return error; }

    public static class NotFound extends ApiException {
        public NotFound(String what, String id) { super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " '" + id + "' not found"); }
        public NotFound(String message) { super(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
    }
    public static class BadRequest extends ApiException {
        public BadRequest(String message) { super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message); }
    }
    public static class Conflict extends ApiException {
        public Conflict(String message) { super(HttpStatus.CONFLICT, "CONFLICT", message); }
    }
    public static class Forbidden extends ApiException {
        public Forbidden(String message) { super(HttpStatus.FORBIDDEN, "FORBIDDEN", message); }
    }
    public static class Unauthorized extends ApiException {
        public Unauthorized(String message) { super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message); }
    }
    public static class NotImplemented extends ApiException {
        public NotImplemented(String message) { super(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", message); }
    }
    public static class ServiceUnavailable extends ApiException {
        public ServiceUnavailable(String message) { super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message); }
    }
}
