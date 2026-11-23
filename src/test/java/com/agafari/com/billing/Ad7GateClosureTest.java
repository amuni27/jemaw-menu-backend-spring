package com.agafari.com.billing;

import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.BillingFlags.MarketFlags;
import com.agafari.com.config.FlagFacts;
import com.agafari.com.config.FlagMatrixValidator;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.subscription.ProviderGrace;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF gate 1 (register §88): "AD-7 grace duration" is closed because AD-7 is decided and implemented. Only that gate is removed:
 * the other eight stay, in order, and billing stays refused at startup. The AD-7 rule itself is pinned here and in the R5-03
 * suites (RenewalFlowTest S15/S16, PaymentOutcomeServiceTest, PaidScheduledChangeTest, EffectiveStateTest, AuthenticStripeFlowTest).
 */
class Ad7GateClosureTest {

    private static final java.util.List<String> REMAINING = java.util.List.of(
            "R7b escalation of an unresolved payment at the retry ceiling (PAYMENT_OUTCOME_PENDING_REVIEW)",
            "Ownership of an exception for an unattributable payment",
            "SUB-R4-11 financial exception handling and payment-under-review block",
            "SUB-R4-13 refund execution",
            "SUB-R4-14 crash-before-TX-B and CREATING recovery",
            "SUB-R4-16 financial concurrency suite",
            "Provider adapter identity validation",
            "Full mutation verification");

    @Test
    void exactlyTheAd7GateIsClosedAndTheOtherEightRemainUnchanged() {
        assertThat(ProviderActivationReadiness.openGates()).containsExactlyElementsOf(REMAINING);
        assertThat(ProviderActivationReadiness.ready()).isFalse();
    }

    @Test
    void billingIsStillRefusedAtStartupInBothMarketsNamingTheRemainingGates() {
        BillingFlags on = new BillingFlags(true, true, true, new MarketFlags(true, true, false, false), new MarketFlags(true, true, false, false));
        var violations = FlagMatrixValidator.violations(on, true, new FlagFacts(true, true, true, true, true, true, true));
        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(v -> {
            assertThat(v).contains("requires provider activation readiness");
            REMAINING.forEach(g -> assertThat(v).contains(g));
            assertThat(v).doesNotContain("AD-7");
        });
    }

    @Test
    void ad7IsSevenDaysForProviderRowsAndNoOtherDurationIsAccepted() {
        assertThat(ProviderGrace.DAYS).isEqualTo(7);
        assertThat(ProviderGrace.require(null)).isEqualTo(7);
        for (int other : new int[] {0, 1, 6, 8, 14, -7}) {
            assertThatThrownBy(() -> ProviderGrace.require(other)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void trialManualAndOwnerRowsRecordNoGrace() {
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        assertThat(BusinessSubscription.trial("b", 1, now, now.plusSeconds(86_400), "system").getGraceDays()).isNull();
        assertThat(BusinessSubscription.manual("b", "MODEL_4", 1, now.plusSeconds(86_400), now, "admin").getGraceDays()).isNull();
        assertThat(BusinessSubscription.owner("b", "MODEL_2", 1, now, "owner").getGraceDays()).isNull();
    }
}
