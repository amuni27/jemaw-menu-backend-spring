package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * AGF E4-M7: one Agafari-managed ad (Flyway V17). Schedule as UTC instants (start inclusive, end exclusive) plus the zone it
 * was entered in, for display only (D-M7-1). Never deleted; ARCHIVED rows are frozen (DB trigger).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "agafari_ad")
public class AgafariAd {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

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

    public static AgafariAd create(Content content, Instant now, String adminId) {
        AgafariAd ad = new AgafariAd();
        ad.id = UUID.randomUUID().toString();
        ad.status = ACTIVE;
        ad.createdAt = now;
        ad.createdBy = adminId;
        ad.apply(content, now, adminId);
        return ad;
    }

    /** The editable fields, already validated. */
    public record Content(String title, String body, String titleAm, String bodyAm, String linkUrl, Instant startsAt, Instant endsAt, String scheduleTimeZone) {
    }

    public void apply(Content c, Instant now, String adminId) {
        title = c.title();
        body = c.body();
        titleAm = c.titleAm();
        bodyAm = c.bodyAm();
        linkUrl = c.linkUrl();
        startsAt = c.startsAt();
        endsAt = c.endsAt();
        scheduleTimeZone = c.scheduleTimeZone();
        touch(now, adminId);
    }

    public void changeStatus(String status, Instant now, String adminId) {
        this.status = status;
        touch(now, adminId);
    }

    public void changeImage(String imageKey, String imageAlt, Instant now, String adminId) {
        this.imageKey = imageKey;
        this.imageAlt = imageAlt;
        touch(now, adminId);
    }

    /** Servable now: ACTIVE, started (inclusive) and not ended (exclusive). Paused, archived and expired ads never are. */
    public boolean isServable(Instant now) {
        return ACTIVE.equals(status) && !startsAt.isAfter(now) && (endsAt == null || now.isBefore(endsAt));
    }

    private void touch(Instant now, String adminId) {
        updatedAt = now;
        updatedBy = adminId;
    }
}
