package org.dbplatform.controlplane.api.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getStatus().value(), e.getError(), e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<String> details = e.getBindingResult().getAllErrors().stream()
                .map(err -> (err instanceof FieldError fe ? fe.getField() + ": " : "") + err.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(new ApiError(400, "VALIDATION_ERROR", "Request validation failed: " + String.join("; ", details), req.getRequestURI(), details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(ConstraintViolationException e, HttpServletRequest req) {
        List<String> details = e.getConstraintViolations().stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList();
        return ResponseEntity.badRequest().body(new ApiError(400, "VALIDATION_ERROR", "Request validation failed: " + String.join("; ", details), req.getRequestURI(), details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest req) {
        String msg = e.getMessage();
        if (e instanceof HttpMessageNotReadableException hm && hm.getCause() instanceof InvalidFormatException ife) {
            msg = "Invalid value '" + ife.getValue() + "' for " + ife.getPathReference();
        } else if (e instanceof HttpMessageNotReadableException) {
            msg = "Malformed request body" + (msg != null ? ": " + msg.split("\n")[0] : "");
        }
        return ResponseEntity.badRequest().body(new ApiError(400, "BAD_REQUEST", msg, req.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        String root = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(409, "CONFLICT", "Constraint violation: " + firstLine(root), req.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return ResponseEntity.status(405).body(new ApiError(405, "METHOD_NOT_ALLOWED", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404).body(new ApiError(404, "NOT_FOUND", "No such endpoint", req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(500).body(new ApiError(500, "INTERNAL_ERROR", firstLine(e.getMessage()), req.getRequestURI()));
    }

    private static String firstLine(String s) {
        if (s == null) return "unexpected error";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
