package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** AGF SUB-R3-01: one version of a price (Flyway V22). Append-only: a change is a new version. */
@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "plan_price")
public class PlanPrice implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "plan_code", nullable = false, length = 16)
    private String planCode;

    @Column(name = "market", nullable = false, length = 2)
    private String market;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "billing_interval", nullable = false, length = 8)
    private String interval;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "provider_price_ref")
    private String providerPriceRef;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    public static PlanPrice version(String planCode, String market, String currency, String interval, long amountMinor, int version, String providerPriceRef,
                                    boolean active, Instant validFrom) {
        PlanPrice p = new PlanPrice();
        p.id = UUID.randomUUID().toString();
        p.planCode = planCode;
        p.market = market;
        p.currency = currency;
        p.interval = interval;
        p.amountMinor = amountMinor;
        p.version = version;
        p.providerPriceRef = providerPriceRef;
        p.active = active;
        p.validFrom = validFrom;
        return p;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Transient
    private boolean persisted;

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
