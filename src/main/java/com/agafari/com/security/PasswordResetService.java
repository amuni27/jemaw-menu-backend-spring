package com.agafari.com.security;

import com.agafari.com.exception.PasswordResetLinkException;
import com.agafari.com.exception.PasswordResetUnavailableException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Password reset by email link (AGF-S2-13A). Requests are answered identically for every email; links are stateless
 * signed tokens ({@link PasswordResetTokens}); a reset only succeeds while the password hash is still the one the
 * link was issued for, checked and changed in a single conditional update, so a link works at most once even when
 * submitted concurrently. Existing sessions (JWTs) stay valid until they expire.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetTokens tokens;
    private final PasswordResetMailer mailer;
    private final ExecutorService passwordResetDeliveryExecutor;

    @Value("${app.password-reset.link-base-url:}")
    private String linkBaseUrl;

    /** Sends a link if the email belongs to an account. Returns the same way whether it does or not. */
    public void requestReset(String rawEmail) {
        String email = normalize(rawEmail);
        Optional<User> user = userRepo.findByEmail(email);
        log.info("event=password_reset.requested email={}", LogMasking.maskEmail(email));

        if (!tokens.enabled() || linkBaseUrl == null || linkBaseUrl.isBlank()) {
            log.warn("event=password_reset.unavailable email={}", LogMasking.maskEmail(email));
            return;
        }

        // A token is made either way, so a registered email costs the same as an unknown one
        String token = user.map(u -> tokens.issue(u.getId(), u.getPasswordHash())).orElseGet(tokens::issueDecoy);
        if (user.isEmpty()) return;

        // The token travels in the URL fragment, which browsers never send to a server
        String link = linkBaseUrl.replaceAll("/+$", "") + "#token=" + token;
        String to = user.get().getEmail();
        passwordResetDeliveryExecutor.execute(() -> {
            try {
                mailer.sendResetLink(to, link);
            } catch (RuntimeException e) {
                log.warn("event=password_reset.delivery_failed email={} errorClass={}", LogMasking.maskEmail(to), e.getClass().getSimpleName());
            }
        });
    }

    /** Sets a new password from a valid link; the link stops working afterwards. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (!tokens.enabled()) throw new PasswordResetUnavailableException();

        PasswordResetTokens.Claims claims = tokens.verify(token).orElseThrow(() -> invalid("signature_or_expiry"));
        User user = userRepo.findById(claims.userId()).orElseThrow(() -> invalid("unknown_user"));
        String currentHash = user.getPasswordHash();
        if (!tokens.boundTo(claims, currentHash)) throw invalid("password_changed");

        int updated = userRepo.updatePasswordHashIfUnchanged(user.getId(), currentHash, passwordEncoder.encode(newPassword));
        if (updated != 1) throw invalid("password_changed");

        log.info("event=password_reset.completed userId={}", user.getId());
    }

    private static PasswordResetLinkException invalid(String reason) {
        log.info("event=password_reset.rejected reason={}", reason);
        return new PasswordResetLinkException();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
