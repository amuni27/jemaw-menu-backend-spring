package com.agafari.com.jpa.entity;

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
    private Instant transitionDate; // reserved for E4-M6 (E4-Q11); M3 never sets it

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

    public boolean isCurrent() {
        return !EXPIRED.equals(status);
    }

    /** Ends this subscription; afterwards the row is frozen (DB trigger). */
    public void end(Instant now, String adminId) {
        status = EXPIRED;
        endedAt = now;
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
