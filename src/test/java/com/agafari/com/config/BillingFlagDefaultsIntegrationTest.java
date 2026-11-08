package com.agafari.com.config;

import com.agafari.com.jobs.JobPoller;
import com.agafari.com.service.ScheduledChangeService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.PeriodEndCadence;
import com.agafari.com.subscription.ScheduledChangeCadence;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R2-12: the real application, with nothing configured, has every billing, enforcement and job switch off. */
class BillingFlagDefaultsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ApplicationContext context;
    @Autowired private BillingFlags flags;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private ScheduledChangeService scheduledChanges;

    @Test
    void everySwitchIsOffAndNothingPolls() {
        assertThat(flags.periodEndEnabled()).isFalse();
        assertThat(flags.billingEnabledInAnyMarket()).isFalse();
        assertThat(evaluator.isPeriodEndEnabled()).isFalse();
        assertThat(scheduledChanges.isBillingEnabled()).isFalse();
        assertThat(context.getBeanNamesForType(JobPoller.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PeriodEndCadence.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduledChangeCadence.class)).isEmpty();
    }

    @Test
    void theValidatorRunsInTheRealApplication() {
        assertThat(context.getBeanNamesForType(FlagMatrixValidator.class)).hasSize(1);
    }
}
