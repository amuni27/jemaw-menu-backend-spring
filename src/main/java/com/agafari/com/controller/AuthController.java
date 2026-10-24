package com.agafari.com.controller;

import com.agafari.com.dto.request.ForgotPasswordRequest;
import com.agafari.com.dto.request.ResetPasswordRequest;
import com.agafari.com.security.PasswordResetService;
import com.agafari.com.dto.request.AuthLoginRequest;
import com.agafari.com.dto.request.AuthRegisterRequest;
import com.agafari.com.dto.request.ChangePasswordRequest;
import com.agafari.com.dto.response.AuthRegisterResponse;
import com.agafari.com.dto.response.AuthTokenResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.security.AuthRateLimiter;
import com.agafari.com.security.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;
    private final PasswordResetService passwordResetService;

    @PostMapping("/register")
    public ResponseEntity<AuthRegisterResponse> register(@Valid @RequestBody AuthRegisterRequest req, HttpServletRequest http) {
        rateLimiter.countRegistration(http.getRemoteAddr()); // AGF-S1-19
        return ResponseEntity.status(201).body(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody AuthLoginRequest req, HttpServletRequest http) {
        // AGF-S1-19: failed logins are limited per email and per client IP; success clears the email's count
        String ip = http.getRemoteAddr();
        rateLimiter.checkLogin(req.getEmail(), ip);
        try {
            AuthTokenResponse response = authService.login(req);
            rateLimiter.loginSucceeded(req.getEmail());
            return ResponseEntity.ok(response);
        } catch (BadRequestException invalidCredentials) {
            rateLimiter.loginFailed(req.getEmail(), ip);
            throw invalidCredentials;
        }
    }

    @GetMapping("/me")
    public ResponseEntity<AuthTokenResponse> me(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(authService.me(principal.getUsername()));
    }

    // AGF-S2-13A: the same 202 answer for every email; a link is sent only to registered ones
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest http) {
        rateLimiter.countForgotPassword(req.getEmail(), http.getRemoteAddr());
        passwordResetService.requestReset(req.getEmail());
        return ResponseEntity.status(202).body(Map.of("message",
                "If an account exists for that email, we've sent a link to reset the password. The link expires in 30 minutes."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest req, HttpServletRequest http) {
        rateLimiter.countResetPassword(http.getRemoteAddr());
        passwordResetService.resetPassword(req.getToken(), req.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Your password has been changed. Sign in with your new password."));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody ChangePasswordRequest req,
            HttpServletRequest http) {
        rateLimiter.countChangePassword(http.getRemoteAddr()); // AGF-S1-19
        authService.changePassword(principal.getUsername(), req);
        return ResponseEntity.ok(Map.of("message", "Password changed"));
    }
}
