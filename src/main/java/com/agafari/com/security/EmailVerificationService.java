package com.agafari.com.security;

import com.agafari.com.exception.EmailDeliveryFailedException;
import com.agafari.com.exception.EmailNotVerifiedException;
import com.agafari.com.exception.EmailVerificationLinkException;
import com.agafari.com.exception.EmailVerificationUnavailableException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

/**
 * Email verification (AGF-S2-22, option B, proposal §11). Registration and sign-in are unchanged. A verification link is
 * sent after registration and after an email change; verifying is one conditional update, so it happens at most once
 * even when the same link is submitted concurrently. The publish gate applies only while the enforcement flag is on
 * AND verification is operational (a valid secret, a link address and a mailer that can deliver); otherwise publishing
 * is unaffected (fail open). Links are never logged; emails in logs are masked.
 */
@Slf4j
@Service
public class EmailVerificationService {

    public enum Outcome { VERIFIED, ALREADY_VERIFIED }

    private final UserRepository userRepo;
    private final EmailVerificationTokens tokens;
    private final EmailVerificationMailer mailer;
    private final ExecutorService deliveryExecutor;
    private final String linkBaseUrl;
    private volatile boolean enforcePublishGate;
    private volatile Clock clock = Clock.systemUTC();

    public EmailVerificationService(UserRepository userRepo, EmailVerificationTokens tokens, EmailVerificationMailer mailer,
                                    ExecutorService emailVerificationDeliveryExecutor,
                                    @Value("${app.email-verification.link-base-url:}") String linkBaseUrl,
                                    @Value("${app.email-verification.enforce-publish-gate:false}") boolean enforcePublishGate) {
        this.userRepo = userRepo;
        this.tokens = tokens;
        this.mailer = mailer;
        this.deliveryExecutor = emailVerificationDeliveryExecutor;
        this.linkBaseUrl = linkBaseUrl == null ? "" : linkBaseUrl.trim();
        this.enforcePublishGate = enforcePublishGate;
    }

    @PostConstruct
    void reportConfiguration() {
        if (enforcePublishGate && !operational()) {
            log.warn("event=email_verification.gate_inactive reason=\"enforcement requested but verification is not operational\"");
        }
    }

    /** A link can be issued, built and delivered. */
    public boolean operational() {
        return tokens.enabled() && !linkBaseUrl.isEmpty() && mailer.operational();
    }

    /** Publishing requires a verified email only while the flag is on and verification is operational. */
    public boolean publishGateEnabled() {
        return enforcePublishGate && operational();
    }

    /** Throws {@link EmailNotVerifiedException} when this user may not publish a menu now. */
    public void requireMayPublish(String userId) {
        if (!publishGateEnabled()) return;
        User user = userRepo.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getEmailVerifiedAt() == null) {
            log.info("event=email_verification.publish_refused userId={}", userId);
            throw new EmailNotVerifiedException();
        }
    }

    /** After the current transaction commits, sends a link in the background (registration, email change). */
    public void sendAfterCommit(String userId, String email) {
        if (!operational()) {
            log.info("event=email_verification.unavailable email={}", LogMasking.maskEmail(email));
            return;
        }
        Runnable send = () -> deliveryExecutor.execute(() -> {
            try {
                deliver(userId, email);
            } catch (RuntimeException e) {
                log.warn("event=email_verification.delivery_failed email={} errorClass={}", LogMasking.maskEmail(email), e.getClass().getSimpleName());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /**
     * Sends a new link now, for a signed-in owner (resend). Returns the address it was sent to, or empty when the email
     * is already verified (nothing sent). Throws {@link EmailDeliveryFailedException} when the email service doesn't
     * accept the link.
     */
    public java.util.Optional<String> resend(String userId) {
        if (!operational()) throw new EmailVerificationUnavailableException();
        User user = userRepo.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getEmailVerifiedAt() != null) return java.util.Optional.empty();
        try {
            deliver(user.getId(), user.getEmail());
            return java.util.Optional.of(user.getEmail());
        } catch (RuntimeException e) {
            log.warn("event=email_verification.delivery_failed email={} errorClass={}", LogMasking.maskEmail(user.getEmail()), e.getClass().getSimpleName());
            throw new EmailDeliveryFailedException();
        }
    }

    /** Verifies the account a link was issued for. A replay on the same, now verified email reports ALREADY_VERIFIED. */
    @Transactional
    public Outcome verify(String token) {
        if (!tokens.enabled()) throw new EmailVerificationUnavailableException();
        EmailVerificationTokens.Claims claims = tokens.verify(token).orElseThrow(() -> invalid("signature_or_expiry"));
        User user = userRepo.findById(claims.userId()).orElseThrow(() -> invalid("unknown_user"));
        String email = user.getEmail();
        if (!tokens.boundTo(claims, email)) throw invalid("email_changed");

        if (userRepo.markEmailVerifiedIfUnverified(user.getId(), email, clock.instant()) == 1) {
            log.info("event=email_verification.verified userId={}", user.getId());
            return Outcome.VERIFIED;
        }
        // Lost a race or replayed: the row is re-read (the update cleared the persistence context)
        User now = userRepo.findById(user.getId()).orElseThrow(() -> invalid("unknown_user"));
        if (now.getEmailVerifiedAt() != null && email.equals(now.getEmail())) return Outcome.ALREADY_VERIFIED;
        throw invalid("email_changed");
    }

    /** Whether this user's email is verified. */
    public boolean verified(User user) {
        return user.getEmailVerifiedAt() != null;
    }

    /** Turns enforcement on or off (tests; production uses {@code APP_EMAIL_VERIFICATION_ENFORCE}). */
    public void setEnforcePublishGate(boolean enforce) {
        this.enforcePublishGate = enforce;
    }

    private void deliver(String userId, String email) {
        // The token travels in the URL fragment, which browsers never send to a server
        String link = linkBaseUrl.replaceAll("/+$", "") + "#token=" + tokens.issue(userId, email);
        mailer.sendVerificationLink(email, link);
        log.info("event=email_verification.sent email={}", LogMasking.maskEmail(email));
    }

    private static EmailVerificationLinkException invalid(String reason) {
        log.info("event=email_verification.rejected reason={}", reason);
        return new EmailVerificationLinkException();
    }
}
