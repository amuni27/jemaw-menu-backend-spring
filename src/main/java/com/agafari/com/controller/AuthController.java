package com.agafari.com.controller;

import com.agafari.com.dto.request.ForgotPasswordRequest;
import com.agafari.com.dto.request.ResetPasswordRequest;
import com.agafari.com.dto.request.VerifyEmailRequest;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.security.EmailVerificationService;
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
    private final EmailVerificationService emailVerification; // AGF-S2-22
    private final CurrentUser currentUser;

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

    // AGF-S2-22: completes verification from a link; a replay on a verified email answers ALREADY_VERIFIED
    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestBody VerifyEmailRequest req, HttpServletRequest http) {
        rateLimiter.countVerifyEmail(http.getRemoteAddr());
        EmailVerificationService.Outcome outcome = emailVerification.verify(req.getToken());
        return ResponseEntity.ok(Map.of("status", outcome.name(), "message",
                outcome == EmailVerificationService.Outcome.VERIFIED ? "Your email is verified." : "Your email is already verified."));
    }

    // AGF-S2-22: honest answers; only links the email service accepted count toward the owner's limit
    @PostMapping("/verify-email/resend")
    public ResponseEntity<Map<String, String>> resendVerification(HttpServletRequest http) {
        String userId = currentUser.userId();
        rateLimiter.checkVerificationResend(userId, http.getRemoteAddr());
        return emailVerification.resend(userId)
                .map(email -> {
                    rateLimiter.verificationResendSent(userId);
                    return ResponseEntity.status(202).body(Map.of("message",
                            "We sent a new link to " + email + ". It can take a few minutes; check your spam folder."));
                })
                .orElseGet(() -> ResponseEntity.ok(Map.of("message", "Your email is already verified.")));
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
