package com.agafari.com.service;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.ScheduledChange;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.EffectiveView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AGF E4-M3: the one place that answers "what may this restaurant use?". Future approved stories (ads, limits, trial
 * expiry, transitions) ask this service instead of reading plans or subscriptions.
 * <p>
 * Today's behaviour ({@link Entitlements#unrestricted()}) applies unless the activation flag
 * {@code app.monetization.entitlements-enabled} (default false) is on <b>and</b> monetization is configured (E4-M2)
 * <b>and</b> the restaurant has a current subscription. Nothing in owner or guest code calls this yet.
 * <p>
 * On any error the answer is unrestricted with {@code degraded = true}. That fail-open fallback is an M3 compatibility
 * measure only (nothing is enforced yet); it is <b>not</b> an approved policy for future paid-feature enforcement: each
 * story that enforces a feature must define and get approval for its own failure behaviour.
 */
@Slf4j
@Service
public class EntitlementService {

    private final MonetizationConfigRepository configs;
    private final BusinessSubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final MonetizationClock clock;
    private final GrandfatheringService grandfathering;
    private final EffectiveStateEvaluator evaluator;
    private volatile boolean enabled;

    public EntitlementService(MonetizationConfigRepository configs, BusinessSubscriptionRepository subscriptions,
                              SubscriptionPlanRepository plans, MonetizationClock clock,
                              @Value("${app.monetization.entitlements-enabled:false}") boolean enabled, GrandfatheringService grandfathering,
                              EffectiveStateEvaluator evaluator) {
        this.evaluator = evaluator;
        this.grandfathering = grandfathering;
        this.configs = configs;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.clock = clock;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private Entitlements applied(ScheduledChange change) {
        if (change.getTargetPlanCode() == null) return Entitlements.unrestricted();
        SubscriptionPlan target = plans.findById(change.getTargetPlanCode()).orElseThrow();
        Entitlements.AdSource adSource = Entitlements.AdSource.valueOf(target.getAdSource());
        return new Entitlements(Entitlements.Mode.ACTIVE, target.getCode(), "ACTIVE", adSource, adSource == Entitlements.AdSource.RESTAURANT, Map.of(), BusinessSubscription.OWNER, false);
    }

    public Entitlements forBusiness(String businessId) {
        if (!enabled) return Entitlements.unrestricted();
        try {
            MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
            if (config == null) return Entitlements.unrestricted();
            // E4-M5: an effectively ended trial counts as no current subscription, also before the expiry job runs; E4-M6: so
            // does a grandfathered subscription past its approved end, with transitions enabled; SUB-R2-07: so does a row past
            // its period end (and grace), with period-end-enabled on
            BusinessSubscription subscription = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
            if (subscription == null) return Entitlements.unrestricted(); // launch defaults stay open (E4-Q10)
            EffectiveView view = evaluator.view(subscription, grandfathering.effectivelyEnded(subscription, config), clock.now());
            // AGF SUB-R2-11: from effective_at, a cancellation or Free downgrade counts as applied, also before the job persists it
            ScheduledChange due = subscriptions.findDueScheduledChange(subscription.getId(), clock.now()).orElse(null);
            if (due != null) return applied(due);
            if (!view.entitledToPlan()) return Entitlements.unrestricted();
            SubscriptionPlan plan = plans.findById(subscription.getPlanCode()).orElseThrow();
            Entitlements.AdSource adSource = Entitlements.AdSource.valueOf(plan.getAdSource());
            return new Entitlements(Entitlements.Mode.ACTIVE, plan.getCode(), view.status().name(), adSource,
                    adSource == Entitlements.AdSource.RESTAURANT, Map.of(), subscription.getSource(), false);
        } catch (RuntimeException e) {
            log.warn("event=entitlements.degraded businessId={} error={}", businessId, e.getClass().getSimpleName());
            return Entitlements.degradedFallback();
        }
    }
}
