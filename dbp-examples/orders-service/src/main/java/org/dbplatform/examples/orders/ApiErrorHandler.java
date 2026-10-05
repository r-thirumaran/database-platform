package org.dbplatform.examples.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.Map;

/** Turns database errors into small JSON bodies; business errors raised by the PL/SQL API become 422. */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> dataAccess(DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        String message = root.getMessage() == null ? e.getMessage() : root.getMessage();
        String sqlState = root instanceof SQLException sql ? sql.getSQLState() : null;
        HttpStatus status = isBusinessError(message, sqlState)
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("Database error", e);
        }
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "sqlState", sqlState == null ? "" : sqlState,
                "message", message == null ? "" : firstLine(message)));
    }

    /** ORA-20xxx (RAISE_APPLICATION_ERROR) and PostgreSQL P0xxx (RAISE EXCEPTION) are business errors. */
    static boolean isBusinessError(String message, String sqlState) {
        if (sqlState != null && sqlState.startsWith("P0")) {
            return true;
        }
        return message != null && message.contains("ORA-20");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
