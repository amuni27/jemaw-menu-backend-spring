package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * AGF E4-M8: one restaurant-managed ad (Flyway V18), owned by exactly one restaurant ({@code businessId}, never changed).
 * Schedule as UTC instants (start inclusive, end exclusive) plus the restaurant's zone when saved, for display (Q-M8-7).
 * Lifecycle (Q-M8-2): owner ACTIVE ↔ PAUSED and archive; Super Admin suspend (reason) and reinstate to PAUSED. Never deleted;
 * ARCHIVED rows are frozen (DB trigger).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "restaurant_ad")
public class RestaurantAd {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private String businessId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "title_am", length = 300)
    private String titleAm;

    @Column(name = "body_am", length = 1000)
    private String bodyAm;

    @Column(name = "link_url", nullable = false, length = 4096)
    private String linkUrl;

    @Column(name = "image_key", length = 512)
    private String imageKey;

    @Column(name = "image_alt", length = 1000)
    private String imageAlt;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "schedule_time_zone", nullable = false, length = 64)
    private String scheduleTimeZone;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "suspension_reason", length = 2000)
    private String suspensionReason;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private Integer rowVersion;

    public static RestaurantAd create(String businessId, AdContent content, Instant now, String ownerId) {
        RestaurantAd ad = new RestaurantAd();
        ad.id = UUID.randomUUID().toString();
        ad.businessId = businessId;
        ad.status = ACTIVE;
        ad.createdAt = now;
        ad.createdBy = ownerId;
        ad.apply(content, now, ownerId);
        return ad;
    }

    /** Content and schedule only; the status never changes here (also while SUSPENDED). */
    public void apply(AdContent c, Instant now, String userId) {
        title = c.title();
        body = c.body();
        titleAm = c.titleAm();
        bodyAm = c.bodyAm();
        linkUrl = c.linkUrl();
        startsAt = c.startsAt();
        endsAt = c.endsAt();
        scheduleTimeZone = c.scheduleTimeZone();
        touch(now, userId);
    }

    /** Owner moves: ACTIVE ↔ PAUSED and archive. Archiving a suspended ad clears its reason (kept in the audit log). */
    public void changeStatus(String status, Instant now, String userId) {
        this.status = status;
        if (!SUSPENDED.equals(status)) {
            suspensionReason = null;
            suspendedAt = null;
        }
        touch(now, userId);
    }

    public void suspend(String reason, Instant now, String adminId) {
        status = SUSPENDED;
        suspensionReason = reason;
        suspendedAt = now;
        touch(now, adminId);
    }

    public void changeImage(String imageKey, String imageAlt, Instant now, String userId) {
        this.imageKey = imageKey;
        this.imageAlt = imageAlt;
        touch(now, userId);
    }

    /** Servable now: ACTIVE, started (inclusive) and not ended (exclusive). Paused, suspended, archived and expired ads never are. */
    public boolean isServable(Instant now) {
        return ACTIVE.equals(status) && !startsAt.isAfter(now) && (endsAt == null || now.isBefore(endsAt));
    }

    private void touch(Instant now, String userId) {
        updatedAt = now;
        updatedBy = userId;
    }
}
