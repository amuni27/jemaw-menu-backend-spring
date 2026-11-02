package com.agafari.com.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** AGF E4-M5: enables the application's only scheduled job (trial expiry, {@code TrialExpiryJob}). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
