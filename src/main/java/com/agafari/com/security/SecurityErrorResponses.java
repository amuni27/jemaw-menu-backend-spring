package com.agafari.com.security;

import com.agafari.com.exception.AccountInactiveException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON error bodies for errors raised by the security filters, in the same shape as
 * {@link com.agafari.com.exception.ApiError} (AGF-S1-07). Bodies are fixed text: nothing from the request is echoed.
 */
@Component
public class SecurityErrorResponses {

    /** Missing, invalid, expired or orphaned token: 401 AUTH_REQUIRED. */
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> write(response, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
    }

    /** Authenticated, but the role may not use this endpoint: 403 ACCESS_DENIED. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied");
    }

    /** Valid token for a SUSPENDED or INACTIVE account: 403 ACCOUNT_INACTIVE. */
    public void accountInactive(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, AccountInactiveException.CODE, AccountInactiveException.MESSAGE);
    }

    private static void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
