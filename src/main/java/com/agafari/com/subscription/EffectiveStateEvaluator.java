package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * AGF SUB-R2-07: applies {@link EffectiveState} with the {@code period-end-enabled} flag (default off, moving to
 * {@code BillingFlags} in SUB-R2-12) and the grace snapshotted on the row. The one place a running service evaluates a row.
 */
@Component
public class EffectiveStateEvaluator {

    private volatile boolean periodEndEnabled;

    public EffectiveStateEvaluator(@Value("${app.billing.period-end-enabled:false}") boolean periodEndEnabled) {
        this.periodEndEnabled = periodEndEnabled;
    }

    public boolean isPeriodEndEnabled() {
        return periodEndEnabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setPeriodEndEnabled(boolean enabled) {
        this.periodEndEnabled = enabled;
    }

    /** {@code grandfatheringEnded} comes from {@code GrandfatheringService.effectivelyEnded}, unchanged. */
    public EffectiveView view(BusinessSubscription s, boolean grandfatheringEnded, Instant now) {
        return EffectiveState.of(s, GraceSettings.ofSnapshot(s.getGraceDays()), periodEndEnabled, grandfatheringEnded, now);
    }
}
