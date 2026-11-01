package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** AGF E4-M1: one audited Super Admin action (Flyway V11). Insert-only: never updated or deleted (DB trigger). */
@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "admin_audit_event")
public class AdminAuditEvent {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private String actorUserId;

    @Column(name = "actor_label", nullable = false)
    private String actorLabel;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "detail", length = 500)
    private String detail;

    public AdminAuditEvent(Instant occurredAt, String actorUserId, String actorLabel, String action, String targetType,
                           String targetId, String outcome, String detail) {
        this.id = UUID.randomUUID().toString();
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.actorLabel = actorLabel;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.outcome = outcome;
        this.detail = detail;
    }
}
