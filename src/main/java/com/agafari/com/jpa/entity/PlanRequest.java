package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * AGF E4-M4: an owner's plan request (Flyway V14). Never a subscription: the Super Admin fulfils it by recording the E4-M3
 * manual subscription (linked) or declines it; the owner can withdraw it. Resolved requests are frozen (DB trigger).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "plan_request")
public class PlanRequest {

    public static final String PENDING = "PENDING";
    public static final String FULFILLED = "FULFILLED";
    public static final String DECLINED = "DECLINED";
    public static final String WITHDRAWN = "WITHDRAWN";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private String businessId;

    @Column(name = "plan_code", nullable = false, length = 16, updatable = false)
    private String planCode;

    @Column(name = "config_version", nullable = false, updatable = false)
    private Integer configVersion;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private String requestedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_user_id")
    private String resolvedByUserId;

    @Column(name = "subscription_id", length = 36)
    private String subscriptionId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Integer rowVersion;

    public static PlanRequest pending(String businessId, String planCode, int configVersion, Instant now, String ownerId) {
        PlanRequest r = new PlanRequest();
        r.id = UUID.randomUUID().toString();
        r.businessId = businessId;
        r.planCode = planCode;
        r.configVersion = configVersion;
        r.status = PENDING;
        r.requestedAt = now;
        r.requestedByUserId = ownerId;
        return r;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public void fulfil(String subscriptionId, Instant now, String adminId) {
        this.subscriptionId = subscriptionId;
        resolve(FULFILLED, now, adminId);
    }

    public void resolve(String status, Instant now, String userId) {
        this.status = status;
        this.resolvedAt = now;
        this.resolvedByUserId = userId;
    }
}
