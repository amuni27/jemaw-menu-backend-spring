package com.agafari.com.security;

import com.agafari.com.dto.response.AdminLoginResponse;
import com.agafari.com.dto.response.AdminSummary;
import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.exception.AccountInactiveException;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.service.JwtService;
import com.agafari.com.validation.StrongPasswordValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * AGF E4-M1: Super Admin sign-in. Only ACTIVE admins succeed. A wrong password, an unknown email and a non-admin account
 * get the same answer (the endpoint doesn't reveal who is an admin); an inactive admin is told only after a correct
 * password (as S1-07). Every attempt is audited without secrets. Tokens are short-lived.
 */
@Slf4j
@Service
public class AdminAuthService {

    public static final String INVALID = "Invalid credentials";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AdminAuditService audit;
    private final Duration tokenLifetime;
    private final String timingHash; // compared against when the account is unknown, so timing reveals nothing

    public AdminAuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, AdminAuditService audit,
                            @Value("${app.admin.jwt-expires-minutes:30}") long tokenMinutes) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.tokenLifetime = Duration.ofMinutes(tokenMinutes);
        this.timingHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    public AdminLoginResponse login(String rawEmail, String password) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        String label = LogMasking.maskEmail(email);
        Optional<User> found = users.findByEmail(email);
        User admin = found.filter(u -> u.getRole() == UserRole.ADMIN).orElse(null);

        boolean passwordOk = password != null && !StrongPasswordValidator.exceedsBcryptLimit(password)
                && passwordEncoder.matches(password, admin != null ? admin.getPasswordHash() : timingHash);
        if (admin == null || !passwordOk) {
            audit.failure(AdminAuditService.LOGIN_FAILED, admin != null ? admin.getId() : null, label, "invalid_credentials");
            log.warn("event=admin.login.failed email={}", label);
            throw new BadRequestException(INVALID);
        }
        if (admin.getStatus() != UserStatus.ACTIVE) {
            audit.failure(AdminAuditService.LOGIN_FAILED, admin.getId(), label, "account_inactive");
            log.warn("event=admin.login.inactive userId={}", admin.getId());
            throw new AccountInactiveException();
        }

        audit.success(AdminAuditService.LOGIN_SUCCEEDED, admin.getId(), label, null, null);
        log.info("event=admin.login.success userId={}", admin.getId());
        String token = jwtService.signToken(admin.getEmail(), admin.getId(), null, admin.getRole().name(), tokenLifetime);
        return new AdminLoginResponse(token, summary(admin));
    }

    public static AdminSummary summary(User admin) {
        return new AdminSummary(admin.getId(), admin.getEmail(), admin.getFullName(), admin.getRole().name());
    }
}
