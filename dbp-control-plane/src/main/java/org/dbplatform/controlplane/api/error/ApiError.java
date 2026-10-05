package org.dbplatform.controlplane.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Error body: {@code { "status": 404, "error": "NOT_FOUND", "message": "...", "path": "/api/v1/..." }}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String message, String path, List<String> details) {
    public ApiError(int status, String error, String message, String path) { this(status, error, message, path, null); }
}
