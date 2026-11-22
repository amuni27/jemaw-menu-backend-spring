package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * AGF E4-M4: what an owner sees about plans. Only {@code visible=false} while the owner visibility flag is off or
 * monetization isn't configured. No prices, payment or trial data exist; {@code featuresUnaffected} is always true (no
 * enforcement). {@code configVersion} is sent back with a request; the UI doesn't show it.
 * <p>AGF BE-5a (register §49): {@code selectionRequired} is present only while the cut-over instant
 * ({@code app.monetization.plan-selection-required-from}) is configured. True only for a restaurant created at or after it
 * that has never had a subscription or trial, has no pending plan request and no payment under review. Navigation only:
 * nothing (publishing, drafts, requests, trials) is restricted by it.
 * <p>AGF BE-4/BE-5b (register §52 B-7): {@code publishAllowed} is present only while the publish gate is on for the
 * restaurant's market, and is exactly the gate's decision (email verification is checked separately).
 * <p>AGF BE-F0 (register §84 D2): {@code checkoutAvailable} is whether a paid checkout can be started for the restaurant's
 * market now (its billing is on); the frontend offers "Subscribe" only then. Present only together with {@code prices}
 * (billing can only be on where prices are visible), so the response is unchanged while prices are hidden.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OwnerPlanView(boolean visible, String setting, Integer configVersion, List<Plan> plans, Subscription current,
                            PlanRequestSummary pendingRequest, Boolean canRequest, Boolean trialAvailable, Boolean featuresUnaffected,
                            Trial trial, EndedPlan endedPlan, SelfService selfService, List<Price> prices,
                            PaymentUnderReview paymentUnderReview, Boolean trialEligible,
                            Boolean selectionRequired, Boolean publishAllowed, Renewal renewal, Boolean checkoutAvailable) {

    public static OwnerPlanView hidden() {
        return new OwnerPlanView(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * AGF SUB-R5-03-B (addendum §13 "Owner sees"): present only while a PROVIDER subscription's renewal needs the owner's
     * attention. {@code state}: {@code PAST_DUE} (payment failed; access continues until {@code graceUntil}),
     * {@code ENDED_PAYMENT_FAILED} (the plan ended at {@code endedAt} for non-payment) or {@code REFUND_PENDING} (a renewal charge
     * Agafari didn't apply will be refunded). {@code refund} accompanies any state while such a refund is unresolved. Dates are
     * shown in {@code timeZone}, the restaurant's.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Renewal(String state, Instant periodEnd, Instant graceUntil, Instant endedAt, RefundPending refund, String timeZone) {
    }

    /** @param reason {@code PAID_AFTER_EXPIRY} or {@code RENEWAL_DUPLICATE_PERIOD} */
    public record RefundPending(Long amountMinor, String currency, String reason) {
    }

    /**
     * AGF Self-Service Phase A: present only while self-service is on. {@code available}: a free enabled plan can be started
     * now (Setting 2, no current subscription; a pending request needs the owner's explicit confirmation).
     * {@code verificationRequired}: the owner must verify their email first (only while verification is operational, SS-2).
     */
    public record SelfService(boolean available, boolean verificationRequired) {
    }

    /**
     * AGF E4-M6: the owner's view of grandfathering: the approved end (absent when none), whole days left, and whether
     * transitions are on (only then does the plan actually end). Dates are shown in the restaurant's time zone.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Grandfathering(Instant until, Long daysLeft, boolean transitionsEnabled, String timeZone) {
    }

    /** AGF E4-M6: the plan the restaurant's subscription ended on through grandfathering. */
    public record EndedPlan(String planCode, Instant endedAt, String timeZone) {
    }

    /** AGF E4-M5: the restaurant's trial in its effective state only (the owner never sees the persisted status). */
    /** AGF E4-M5: {@code timeZone} is the restaurant's, in which the end date is shown (Option A). */
    public record Trial(Instant startedAt, Instant endsAt, long daysLeft, String effectiveStatus, String timeZone) {
    }

    /** AGF SUB-R3-02: only with {@code prices-visible} on for the restaurant's market. {@code id} is what a later checkout names. */
    public record Price(String id, String planCode, String interval, String currency, long amountMinor) {
    }

    /**
     * AGF SUB-R4-11 (DD §8.5, R-2): present while a payment of this restaurant that involves customer funds is under review.
     * {@code reference} is the review's id, to quote to support; {@code since} is when the earliest open review began.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentUnderReview(Long amountMinor, String currency, Instant paidAt, String reference, Instant since) {
    }

    public record Plan(String code, String model, String adSource, boolean paid) {
    }

    /** The current subscription as the owner sees it: no admin identity, no configuration version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    /**
     * AGF SUB-R5-03-B: {@code graceUntil} ({@code period_end} + the recorded grace) and {@code pastDueSince} exist only for a
     * PROVIDER row (with recorded grace; while past due) and are omitted otherwise, so every other row's JSON is unchanged.
     */
    public record Subscription(String planCode, String status, Instant periodEnd, String sourceLabel, boolean outsideCurrentConfiguration,
                               Grandfathering grandfathering, @JsonInclude(JsonInclude.Include.NON_NULL) Instant graceUntil,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Instant pastDueSince) {

        public Subscription(String planCode, String status, Instant periodEnd, String sourceLabel, boolean outsideCurrentConfiguration, Grandfathering grandfathering) {
            this(planCode, status, periodEnd, sourceLabel, outsideCurrentConfiguration, grandfathering, null, null);
        }
    }
}
