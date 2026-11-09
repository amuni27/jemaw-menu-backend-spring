package com.agafari.com.jpa.entity;

import com.agafari.com.subscription.EffectiveState;
import com.agafari.com.subscription.GraceSettings;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * AGF E4-M3: one subscription period of a restaurant (Flyway V13). At most one is current (TRIAL, ACTIVE or
 * GRANDFATHERED; partial unique index); EXPIRED ones are history and frozen. Plan, configuration version, restaurant and
 * source never change (DB trigger; no setters). M3 creates only ACTIVE manual records and can end them.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "business_subscription")
public class BusinessSubscription {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";
    public static final String MANUAL = "MANUAL";
    public static final String TRIAL = "TRIAL"; // AGF E4-M5: status and source of a trial
    private static final GraceSettings NO_GRACE = GraceSettings.ofDays(0);
    public static final String OWNER = "OWNER"; // AGF Self-Service Phase A: started by the owner (free plan, no payment)

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private String businessId;

    @Column(name = "plan_code", nullable = false, length = 16, updatable = false)
    private String planCode;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "config_version", nullable = false, updatable = false)
    private Integer configVersion;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "transition_date")
    private Instant transitionDate; // AGF E4-M6: only for open-ended grandfathered subscriptions (Option B), set by the Super Admin

    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private String createdByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_user_id", nullable = false)
    private String updatedByUserId;

    @Column(name = "ended_at")
    private Instant endedAt;

    // AGF SUB-R2-06: Flyway V20 lifecycle columns
    @Column(name = "price_id", length = 36)
    private String priceId;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "grace_days")
    private Integer graceDays;

    @Column(name = "past_due_since")
    private Instant pastDueSince;

    @Column(name = "open_ended", nullable = false)
    private boolean openEnded;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "provider_subscription_ref")
    private String providerSubscriptionRef;

    @Column(name = "ended_reason", length = 32)
    private String endedReason;

    @Column(name = "replaced_by_id", length = 36)
    private String replacedById;

    @Version
    @Column(name = "row_version", nullable = false)
    private Integer rowVersion;

    /** A new manual ACTIVE record (E4-Q7: records only, no payment verified). */
    public static BusinessSubscription manual(String businessId, String planCode, int configVersion, Instant periodEnd,
                                              Instant now, String adminId) {
        BusinessSubscription s = new BusinessSubscription();
        s.id = UUID.randomUUID().toString();
        s.businessId = businessId;
        s.planCode = planCode;
        s.status = ACTIVE;
        s.configVersion = configVersion;
        s.periodEnd = periodEnd;
        s.source = MANUAL;
        s.createdAt = now;
        s.createdByUserId = adminId;
        s.updatedAt = now;
        s.updatedByUserId = adminId;
        return s;
    }

    /** AGF Self-Service Phase A: an open-ended ACTIVE subscription the owner started themselves (a free plan only). */
    public static BusinessSubscription owner(String businessId, String planCode, int configVersion, Instant now, String ownerId) {
        BusinessSubscription s = manual(businessId, planCode, configVersion, null, now, ownerId);
        s.source = OWNER;
        return s;
    }

    /** AGF E4-M5: a Model 1 trial ending at {@code periodEnd} (actor "system" at registration, or the admin). */
    public static BusinessSubscription trial(String businessId, int configVersion, Instant now, Instant periodEnd, String actor) {
        BusinessSubscription s = manual(businessId, "MODEL_1", configVersion, periodEnd, now, actor);
        s.status = TRIAL;
        s.source = TRIAL;
        return s;
    }

    /** AGF SUB-R4-09: a paid subscription; only {@code SubscriptionLifecycle} creates one, with verified evidence. */
    public static BusinessSubscription provider(String businessId, String planCode, int configVersion, String priceId, String provider, String providerSubscriptionRef, Integer graceDays,
                                                Instant periodStart, Instant periodEnd, Instant now, String actor) {
        BusinessSubscription s = manual(businessId, planCode, configVersion, periodEnd, now, actor);
        s.source = "PROVIDER";
        s.priceId = priceId;
        s.provider = provider;
        s.providerSubscriptionRef = providerSubscriptionRef;
        s.graceDays = graceDays;
        s.currentPeriodStart = periodStart;
        return s;
    }

    /** AGF SUB-R4-09: one more paid period; the row is ACTIVE again. */
    public void renewPeriod(Instant periodStart, Instant periodEnd, Instant now, String actor) {
        status = ACTIVE;
        currentPeriodStart = periodStart;
        this.periodEnd = periodEnd;
        pastDueSince = null;
        touch(now, actor);
    }

    public boolean isTrial() {
        return TRIAL.equals(source);
    }

    /** AGF E4-M5: a trial counts as ended once its period end has passed, even before the job persists it (D-M5-13). */
    public String effectiveStatus(Instant now) {
        return EffectiveState.of(this, NO_GRACE, false, false, now).status().name(); // AGF SUB-R2-07: one rule; the period-end flag is applied by EffectiveStateEvaluator
    }

    public Instant effectiveEnd(Instant now) {
        if (endedAt != null) return endedAt;
        return EXPIRED.equals(effectiveStatus(now)) ? periodEnd : null;
    }

    public boolean isEffectivelyCurrent(Instant now) {
        return EffectiveState.of(this, NO_GRACE, false, false, now).entitledToPlan();
    }

    /** AGF E4-M5: ends a trial at {@code endedAt} (conversion: the earlier of now and the period end). */
    public void endTrial(Instant endedAt, Instant now, String adminId) {
        status = EXPIRED;
        this.endedAt = endedAt;
        touch(now, adminId);
    }

    /** AGF SUB-R2-06: why this row ended and its successor, if any. Set with the end itself: an ended row is frozen. */
    public void recordEnding(String endedReason, String replacedById) {
        this.endedReason = endedReason;
        this.replacedById = replacedById;
    }

    /** AGF SUB-R2-07: persists an effective end at the instant it took effect (period end or grace end). */
    public void endAtEffectiveTime(Instant endedAt, String endedReason, Instant now, String actor) {
        status = EXPIRED;
        this.endedAt = endedAt;
        this.endedReason = endedReason;
        pastDueSince = null;
        touch(now, actor);
    }

    /** AGF SUB-R2-07: persists the grace state of a PROVIDER row. */
    public void markPastDue(Instant since, Instant now, String actor) {
        status = "PAST_DUE";
        pastDueSince = since;
        touch(now, actor);
    }

    public boolean isCurrent() {
        return !EXPIRED.equals(status);
    }

    /** Ends this subscription; afterwards the row is frozen (DB trigger). */
    public void end(Instant now, String adminId) {
        status = EXPIRED;
        endedAt = now;
        pastDueSince = null; // AGF SUB-R2-06: set only while PAST_DUE (DB check)
        touch(now, adminId);
    }

    /** AGF E4-M6: the exclusive end of an open-ended grandfathered subscription (restaurant-local calendar rule), or null to clear. */
    public void changeTransitionDate(Instant transitionDate, Instant now, String adminId) {
        this.transitionDate = transitionDate;
        touch(now, adminId);
    }

    public void changePeriodEnd(Instant periodEnd, Instant now, String adminId) {
        this.periodEnd = periodEnd;
        touch(now, adminId);
    }

    private void touch(Instant now, String adminId) {
        updatedAt = now;
        updatedByUserId = adminId;
    }
}
