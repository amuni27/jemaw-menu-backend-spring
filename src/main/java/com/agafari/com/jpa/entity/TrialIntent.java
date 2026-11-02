package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * AGF E4-M5 recovery: a durable trial intent (Flyway V16), committed with an eligible registration. It keeps the
 * eligibility decision and the configuration version that made the restaurant eligible until the trial is created (after
 * the commit or by the daily retry). Resolved intents are frozen and intents are never deleted (DB trigger). Changes go
 * through the conditional updates in the repository.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "trial_intent")
public class TrialIntent {

    public static final String PENDING = "PENDING";
    public static final String STARTED = "STARTED";
    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String AFTER_COMMIT = "AFTER_COMMIT";
    public static final String RETRY = "RETRY";
    public static final String MANUAL = "MANUAL";
    public static final String EXISTING_TRIAL = "EXISTING_TRIAL";
    public static final String SUBSCRIPTION_EXISTS = "SUBSCRIPTION_EXISTS";

    @Id
    @Column(name = "business_id", nullable = false)
    private String businessId;

    @Column(name = "config_version", nullable = false, updatable = false)
    private Integer configVersion;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "resolution", length = 32)
    private String resolution;

    @Column(name = "subscription_id", length = 36)
    private String subscriptionId;

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", length = 64)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Integer rowVersion;

    public static TrialIntent pending(String businessId, int configVersion, Instant now) {
        TrialIntent i = new TrialIntent();
        i.businessId = businessId;
        i.configVersion = configVersion;
        i.status = PENDING;
        i.failedAttempts = 0;
        i.createdAt = now;
        return i;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }
}
