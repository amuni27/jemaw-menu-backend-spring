package com.agafari.com.security;

import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.validation.StrongPasswordValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * AGF E4-M1: creates the first Super Admin during startup (before the web server starts), once. Runs only when the environment is explicitly authorized
 * ({@code app.admin.bootstrap.environment-authorized}, set only in local/test profile files), the {@code prod} profile is
 * not active, and both {@code APP_ADMIN_BOOTSTRAP_EMAIL} and {@code APP_ADMIN_BOOTSTRAP_PASSWORD} are set. It never resets
 * a password, never promotes an existing account and never creates a second admin. Refusals stop the startup.
 * Credentials come only from the environment; the password is never logged and the email only masked.
 */
@Slf4j
@Component
public class AdminBootstrap implements SmartInitializingSingleton {

    public enum Result { NOT_REQUESTED, CREATED, ALREADY_BOOTSTRAPPED }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService audit;
    private final Environment environment;
    private final TransactionTemplate transaction;
    private final String email;
    private final String password;
    private final boolean environmentAuthorized;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AdminAuditService audit, Environment environment,
                          PlatformTransactionManager transactionManager,
                          @Value("${app.admin.bootstrap.email:}") String email,
                          @Value("${app.admin.bootstrap.password:}") String password,
                          @Value("${app.admin.bootstrap.environment-authorized:false}") boolean environmentAuthorized) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.environment = environment;
        this.transaction = new TransactionTemplate(transactionManager); // the admin and its audit row commit together
        this.email = email;
        this.password = password;
        this.environmentAuthorized = environmentAuthorized;
    }

    /** Runs once every component (incl. Flyway and JPA) is ready but before the web server starts: a refusal stops the
     *  application before it serves any request (fail closed). */
    @Override
    public void afterSingletonsInstantiated() {
        bootstrap(email, password, environmentAuthorized, environment.acceptsProfiles(Profiles.of("prod")));
    }

    public Result bootstrap(String rawEmail, String rawPassword, boolean authorized, boolean prodProfile) {
        if (isBlank(rawEmail) || isBlank(rawPassword)) return Result.NOT_REQUESTED;
        if (prodProfile) throw refused("Admin bootstrap is not allowed in production");
        if (!authorized) throw refused("Admin bootstrap is not authorized in this environment");

        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        return transaction.execute(status -> create(normalized, rawPassword));
    }

    private Result create(String normalized, String rawPassword) {
        if (users.existsByRole(UserRole.ADMIN)) {
            log.info("event=admin.bootstrap.skipped reason=already_bootstrapped");
            return Result.ALREADY_BOOTSTRAPPED;
        }
        if (users.findByEmail(normalized).isPresent()) throw refused("The bootstrap email already belongs to an account");
        if (!new StrongPasswordValidator().isValid(rawPassword, null)) {
            throw refused("The bootstrap password doesn't meet the password policy");
        }

        User admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setFullName("Super Admin");
        admin.setEmail(normalized);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerifiedAt(Instant.now()); // S2-22 never applies to the platform admin
        users.save(admin);

        audit.success(AdminAuditService.BOOTSTRAP_CREATED, null, AdminAuditService.SYSTEM, "USER", admin.getId());
        log.info("event=admin.bootstrap.created email={} userId={}", LogMasking.maskEmail(normalized), admin.getId());
        return Result.CREATED;
    }

    private static IllegalStateException refused(String reason) {
        log.error("event=admin.bootstrap.refused reason=\"{}\"", reason);
        return new IllegalStateException(reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
