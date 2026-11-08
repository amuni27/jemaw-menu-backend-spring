package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** AGF SUB-R2-06: a pending end or downgrade of a subscription (Flyway V20). Mapping only; no service writes it yet. */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "scheduled_change")
public class ScheduledChange {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false)
    private String businessId;

    @Column(name = "from_subscription_id", nullable = false, length = 36)
    private String fromSubscriptionId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "target_plan_code", length = 16)
    private String targetPlanCode;

    @Column(name = "target_price_id", length = 36)
    private String targetPriceId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "provider_schedule_ref")
    private String providerScheduleRef;
}
