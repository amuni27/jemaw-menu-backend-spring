package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.util.List;

/**
 * AGF E4-M3: one plan per monetization model (Flyway V13 seed, read-only). Only what the approved requirements fix: the
 * setting it belongs to, its ad source and whether it is paid. No price, trial length or limits (E4-Q2/Q3/Q8 open).
 */
@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "subscription_plan")
public class SubscriptionPlan implements Persistable<String> {

    /** The approved plans, in model order: the V13 seed must match (test) and the H2 demo seeds these. */
    public static final List<SubscriptionPlan> APPROVED = List.of(
            new SubscriptionPlan("MODEL_1", "SETTING_1", "NONE", true),       // free trial, then paid (the trial is a status)
            new SubscriptionPlan("MODEL_2", "SETTING_2", "AGAFARI", false),   // free, Agafari ads
            new SubscriptionPlan("MODEL_3", "SETTING_2", "RESTAURANT", true), // paid, the restaurant's own ads
            new SubscriptionPlan("MODEL_4", "SETTING_2", "NONE", true));      // lower-cost paid, no ads

    @Id
    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    @Column(name = "setting", nullable = false, length = 16)
    private String setting;

    @Column(name = "ad_source", nullable = false, length = 16)
    private String adSource;

    @Column(name = "paid", nullable = false)
    private boolean paid;

    private SubscriptionPlan(String code, String setting, String adSource, boolean paid) {
        this.code = code;
        this.model = code;
        this.setting = setting;
        this.adSource = adSource;
        this.paid = paid;
    }

    /** A fresh instance for persisting (the H2 demo seed); {@link #APPROVED} itself is shared and never persisted. */
    public SubscriptionPlan copy() {
        return new SubscriptionPlan(code, setting, adSource, paid);
    }

    @Override
    public String getId() {
        return code;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
