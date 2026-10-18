package com.agafari.com.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Maps every exception to a consistent, sanitized {@link ApiError} (AGF-S1-10).
 * Client errors return safe, fixed messages; unexpected errors return only a traceId,
 * and the full stack trace is logged under that traceId.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    // ---------- domain errors (messages are written by the application and safe to return) ----------

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> bad(BadRequestException e) {
        return domain(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forb(ForbiddenException e) {
        return domain(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> nf(NotFoundException e) {
        return domain(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(AccountInactiveException.class)
    public ResponseEntity<ApiError> accountInactive(AccountInactiveException e) {
        log.info("event=api.account_inactive");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(HttpStatus.FORBIDDEN, AccountInactiveException.CODE, e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conf(ConflictException e) {
        return domain(HttpStatus.CONFLICT, e);
    }

    // ---------- request errors (framework messages are replaced with fixed, safe text) ----------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<ApiError.FieldErrorDetail> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldErrorDetail(f.getField(), f.getDefaultMessage()))
                .toList();
        log.info("event=api.validation_failed fields={}", fields.stream().map(ApiError.FieldErrorDetail::field).toList());
        return ResponseEntity.badRequest().body(ApiError.validation(fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return client(HttpStatus.BAD_REQUEST, "Malformed JSON request");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return client(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return client(HttpStatus.BAD_REQUEST, "Missing required parameter '" + e.getParameterName() + "'");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) {
            headers.setAllow(e.getSupportedHttpMethods());
        }
        log.info("event=api.method_not_supported method={}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers)
                .body(ApiError.of(HttpStatus.METHOD_NOT_ALLOWED, "Request method '" + e.getMethod() + "' is not supported"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> notFound(Exception e) {
        return client(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException e) {
        // Constraint names and offending values stay in the logs only
        log.warn("event=api.data_integrity_violation error={}", e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT, "Request conflicts with existing data"));
    }

    // ---------- everything else ----------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unknown(Exception e, HttpServletRequest request) {
        // Other Spring web errors (e.g. 415 unsupported media type) keep their status with a generic message
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = er.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            return client(status, known != null ? known.getReasonPhrase() : "Request error");
        }
        String traceId = UUID.randomUUID().toString();
        log.error("event=api.unexpected_error traceId={} method={} path={}", traceId, request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.unexpected(traceId));
    }

    private ResponseEntity<ApiError> domain(HttpStatus status, RuntimeException e) {
        log.info("event=api.domain_error status={} message={}", status.value(), e.getMessage());
        return ResponseEntity.status(status).body(ApiError.of(status, e.getMessage()));
    }

    private ResponseEntity<ApiError> client(HttpStatusCode status, String message) {
        log.info("event=api.client_error status={} message={}", status.value(), message);
        return ResponseEntity.status(status).body(ApiError.of(status, message));
    }
}
