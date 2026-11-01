package com.agafari.com.demo;

import com.agafari.com.security.EmailVerificationMailer;
import com.agafari.com.security.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local demo only (AGF-S2-22): keeps the latest verification link per email in memory instead of sending an email, so
 * the flow can be tried before an email provider exists (D5). The link is never logged.
 * <p>
 * Lives in {@code src/demo} (compiled only with {@code -Pdemo}; not in the production artifact) and refuses to start
 * with the {@code prod} profile or outside the in-memory demo database, like the password-reset outbox.
 */
@Slf4j
@Component
@Profile("demo")
public class DemoEmailVerificationOutbox implements EmailVerificationMailer {

    private final Map<String, String> latestByEmail = new ConcurrentHashMap<>();

    public DemoEmailVerificationOutbox(Environment env, @Value("${spring.datasource.url:}") String datasourceUrl) {
        if (env.acceptsProfiles(Profiles.of("prod")) || !datasourceUrl.startsWith("jdbc:h2:mem:")) {
            throw new IllegalStateException("The demo email-verification outbox may only run in the local demo (never with prod)");
        }
    }

    @Override
    public void sendVerificationLink(String email, String verifyUrl) {
        latestByEmail.put(email.toLowerCase(), verifyUrl);
        log.info("event=email_verification.demo_outbox_stored email={}", LogMasking.maskEmail(email));
    }

    /** The latest link for this email, removed once read. */
    Optional<String> take(String email) {
        return Optional.ofNullable(latestByEmail.remove(email == null ? "" : email.trim().toLowerCase()));
    }
}
