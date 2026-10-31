package com.agafari.com.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Standard error body (AGF-S1-10). {@code message} is kept for frontend compatibility;
 * {@code fieldErrors} appears only for validation errors, {@code traceId} only for unexpected (500) errors.
 * {@code code} is a machine-readable reason for authentication and authorization errors (AGF-S1-07):
 * AUTH_REQUIRED (401), ACCESS_DENIED (403), ACCOUNT_INACTIVE (403).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String code, String message, List<FieldErrorDetail> fieldErrors, String traceId) {

    public record FieldErrorDetail(String field, String message) {
    }

    public static ApiError of(HttpStatusCode status, String message) {
        return new ApiError(status.value(), reason(status), null, message, null, null);
    }

    public static ApiError of(HttpStatusCode status, String code, String message) {
        return new ApiError(status.value(), reason(status), code, message, null, null);
    }

    public static ApiError validation(List<FieldErrorDetail> fieldErrors) {
        return new ApiError(400, reason(HttpStatus.BAD_REQUEST), null, "Validation failed", fieldErrors, null);
    }

    public static ApiError unexpected(String traceId) {
        return new ApiError(500, reason(HttpStatus.INTERNAL_SERVER_ERROR), null, "An unexpected error occurred", null, traceId);
    }

    private static String reason(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }
}
