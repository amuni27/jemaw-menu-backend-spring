package com.agafari.com.controller;

import com.agafari.com.dto.request.AuthLoginRequest;
import com.agafari.com.dto.response.AdminLoginResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.security.AdminAuthService;
import com.agafari.com.security.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AGF E4-M1: Super Admin sign-in (public; the S1-19 login limits apply under their own "admin:" keys). */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuth;
    private final AuthRateLimiter rateLimiter;

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(@Valid @RequestBody AuthLoginRequest req, HttpServletRequest http) {
        String key = "admin:" + req.getEmail();
        String ip = http.getRemoteAddr();
        rateLimiter.checkLogin(key, ip);
        try {
            AdminLoginResponse response = adminAuth.login(req.getEmail(), req.getPassword());
            rateLimiter.loginSucceeded(key);
            return ResponseEntity.ok(response);
        } catch (BadRequestException invalidCredentials) {
            rateLimiter.loginFailed(key, ip);
            throw invalidCredentials;
        }
    }
}
