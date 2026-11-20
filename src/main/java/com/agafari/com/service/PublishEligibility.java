package com.agafari.com.service;

import com.agafari.com.billing.Market;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.FlagMatrixViolationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.PlanRequiredToPublishException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.ScheduledChange;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.subscription.EffectiveState;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.EffectiveView;
import com.agafari.com.subscription.GraceSettings;
import com.agafari.com.subscription.ScheduledChangeRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AGF BE-4 (register §52): whether a restaurant may publish a menu now. Applies only while
 * {@code app.billing.<market>.publish-requires-plan} is on for the restaurant's market (US/ET, from its stored country; B-2:
 * no market, not gated). Then: an existing restaurant (created before the {@link RestaurantCutOver}, B-1) may; a new one
 * may only with an entitled current row, judged exactly as entitlements judge it (EffectiveState with grandfathering and
 * grace, B-5; a due cancellation counts as applied). Pending requests, open checkouts and payments under review create no
 * row, so they never entitle (policy 6). Read only. Callers check only the step into "published" (policy 7).
 */
@Slf4j
@Component
public class PublishEligibility {

    public enum Decision {
        NOT_GATED, EXEMPT, ENTITLED, REFUSED;

        public boolean allowed() {
            return this != REFUSED;
        }
    }

    private final RestaurantCutOver cutOver;
    private final BusinessRepository businesses;
    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final GrandfatheringService grandfathering;
    private final EffectiveStateEvaluator evaluator;
    private final MonetizationClock clock;
    private final Map<Market, Boolean> gate = new EnumMap<>(Market.class);

    public PublishEligibility(BillingFlags flags, RestaurantCutOver cutOver, BusinessRepository businesses, BusinessSubscriptionRepository subscriptions,
                              MonetizationConfigRepository configs, GrandfatheringService grandfathering, EffectiveStateEvaluator evaluator,
                              MonetizationClock clock) {
        this.cutOver = cutOver;
        this.businesses = businesses;
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.grandfathering = grandfathering;
        this.evaluator = evaluator;
        this.clock = clock;
        gate.put(Market.US, flags.us().publishRequiresPlan());
        gate.put(Market.ET, flags.et().publishRequiresPlan());
        // B-1: without the cut-over every restaurant, the existing ones included, would be refused
        if (gate.containsValue(true) && cutOver.from() == null) {
            throw new FlagMatrixViolationException(List.of("publish-requires-plan requires app.monetization.plan-selection-required-from "
                    + "(the existing-restaurant cut-over, register §52 B-1)"));
        }
    }

    public synchronized boolean gateOn(Market market) {
        return Boolean.TRUE.equals(gate.get(market));
    }

    /** Tests only: the flags themselves come from configuration and the flag matrix. */
    public synchronized void setGate(Market market, boolean on) {
        gate.put(market, on);
    }

    /**
     * AGF BE-6: one restaurant's evaluation as if the gate were on, with a reason. {@code reason}: EXEMPT → CREATED_BEFORE_CUT_OVER;
     * ENTITLED → TRIAL, ACTIVE_&lt;SOURCE&gt;, GRANDFATHERED, PAST_DUE_IN_GRACE or DOWNGRADE_DUE; REFUSED → NO_PLAN, PLAN_ENDED,
     * CANCELLATION_DUE or the effective-state reason (TRIAL_ENDED, GRACE_ENDED, GRANDFATHERING_ENDED, …).
     * {@code entitledOnlyBecausePeriodEndOff}: entitled now, but not with period-end processing on (D-2 review list).
     */
    public record Evaluation(Decision decision, String reason, boolean entitledOnlyBecausePeriodEndOff) {
    }

    public Decision decide(String businessId) {
        Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Business not found"));
        Market market = Market.fromCountry(business.getCountry()).orElse(null);
        if (market == null || !gateOn(market)) return Decision.NOT_GATED;
        BusinessSubscription row = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        Instant now = clock.now();
        ScheduledChange due = row == null || config == null ? null : subscriptions.findDueScheduledChange(row.getId(), now).orElse(null);
        return evaluate(business.getCreatedAt(), row, due, config, false, now).decision();
    }

    /**
     * The gate's rule for one restaurant, as if the gate were on in its market (BE-4; shared with the BE-6 dry run so the two
     * can never differ). {@code due}: the row's due scheduled change, if any; {@code hadAnyRow} only shapes the reason.
     */
    public Evaluation evaluate(Instant createdAt, BusinessSubscription row, ScheduledChange due, MonetizationConfig config, boolean hadAnyRow, Instant now) {
        Instant from = cutOver.from(); // never null while a gate is on (startup check); a test that clears it fails closed
        if (from != null && createdAt != null && createdAt.isBefore(from)) return new Evaluation(Decision.EXEMPT, "CREATED_BEFORE_CUT_OVER", false); // P-6, B-1
        if (row == null) return new Evaluation(Decision.REFUSED, hadAnyRow ? "PLAN_ENDED" : "NO_PLAN", false);
        // AGF SUB-R2-11 (as entitlements): from effective_at a cancellation counts as applied, a Free downgrade keeps a plan
        if (due != null && config != null && ScheduledChangeRules.stillApplies(due, config)) {
            return due.getTargetPlanCode() != null ? new Evaluation(Decision.ENTITLED, "DOWNGRADE_DUE", false) : new Evaluation(Decision.REFUSED, "CANCELLATION_DUE", false);
        }
        boolean grandfatheringEnded = config != null && grandfathering.effectivelyEnded(row, config);
        EffectiveView view = evaluator.view(row, grandfatheringEnded, now);
        if (!view.entitledToPlan()) return new Evaluation(Decision.REFUSED, view.reasonCode(), false);
        boolean onlyBecausePeriodEndOff = !evaluator.isPeriodEndEnabled() && !row.isTrial()
                && !EffectiveState.of(row, GraceSettings.ofSnapshot(row.getGraceDays()), true, grandfatheringEnded, now).entitledToPlan();
        String reason = switch (view.status()) {
            case TRIAL -> "TRIAL";
            case GRANDFATHERED -> "GRANDFATHERED";
            case PAST_DUE -> "PAST_DUE_IN_GRACE";
            default -> "ACTIVE_" + row.getSource();
        };
        return new Evaluation(Decision.ENTITLED, reason, onlyBecausePeriodEndOff);
    }

    /** Throws {@link PlanRequiredToPublishException} when this restaurant may not publish now. */
    public void requireMayPublish(String businessId) {
        Decision decision = decide(businessId);
        if (decision.allowed()) return;
        log.info("event=publish_gate.refused businessId={}", businessId);
        throw new PlanRequiredToPublishException();
    }
}
