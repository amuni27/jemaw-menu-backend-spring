package com.agafari.com.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AGF-S2-13A wiring: the default (disabled) mailer and the background thread that delivers links. */
@Configuration
public class PasswordResetConfig {

    /** Replaced only by a provider-backed mailer (after D5) or, in -Pdemo builds, the local demo outbox. */
    @Bean
    @ConditionalOnMissingBean(PasswordResetMailer.class)
    public PasswordResetMailer passwordResetMailer() {
        return new DisabledPasswordResetMailer();
    }

    /** Delivery runs in the background so a registered email is not slower to answer than an unknown one. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService passwordResetDeliveryExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "password-reset-delivery");
            t.setDaemon(true);
            return t;
        });
    }
}
