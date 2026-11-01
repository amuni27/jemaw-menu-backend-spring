package com.agafari.com.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AGF-S2-22 wiring: the default (disabled) mailer and the background thread for links sent after registration. */
@Configuration
public class EmailVerificationConfig {

    /** Replaced only by a provider-backed mailer (after D5), the local demo outbox (-Pdemo) or a test mailer. */
    @Bean
    @ConditionalOnMissingBean(EmailVerificationMailer.class)
    public EmailVerificationMailer emailVerificationMailer() {
        return new DisabledEmailVerificationMailer();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService emailVerificationDeliveryExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "email-verification-delivery");
            t.setDaemon(true);
            return t;
        });
    }
}
