package com.agafari.com.service;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

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
    private volatile boolean enabled;

    public EntitlementService(MonetizationConfigRepository configs, BusinessSubscriptionRepository subscriptions,
                              SubscriptionPlanRepository plans,
                              @Value("${app.monetization.entitlements-enabled:false}") boolean enabled) {
        this.configs = configs;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Entitlements forBusiness(String businessId) {
        if (!enabled) return Entitlements.unrestricted();
        try {
            if (configs.findTopByOrderByVersionDesc().isEmpty()) return Entitlements.unrestricted();
            Optional<BusinessSubscription> current = subscriptions.findCurrentByBusinessId(businessId);
            if (current.isEmpty()) return Entitlements.unrestricted(); // launch defaults stay open (E4-Q10)
            BusinessSubscription subscription = current.get();
            SubscriptionPlan plan = plans.findById(subscription.getPlanCode()).orElseThrow();
            Entitlements.AdSource adSource = Entitlements.AdSource.valueOf(plan.getAdSource());
            return new Entitlements(Entitlements.Mode.ACTIVE, plan.getCode(), subscription.getStatus(), adSource,
                    adSource == Entitlements.AdSource.RESTAURANT, Map.of(), subscription.getSource(), false);
        } catch (RuntimeException e) {
            log.warn("event=entitlements.degraded businessId={} error={}", businessId, e.getClass().getSimpleName());
            return Entitlements.degradedFallback();
        }
    }
}
