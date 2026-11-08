package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static com.agafari.com.subscription.LifecycleStatus.ACTIVE;
import static com.agafari.com.subscription.LifecycleStatus.EXPIRED;
import static com.agafari.com.subscription.LifecycleStatus.GRANDFATHERED;
import static com.agafari.com.subscription.LifecycleStatus.PAST_DUE;
import static com.agafari.com.subscription.LifecycleStatus.TRIAL;
import static com.agafari.com.subscription.SubscriptionSource.MANUAL;
import static com.agafari.com.subscription.SubscriptionSource.OWNER;
import static com.agafari.com.subscription.SubscriptionSource.PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R2-01: read-time effective state for every source × boundary (TA Rev. 3 §4.3; Backend Detailed Design Rev. 2 §3.2).
 * Boundaries: one millisecond before the period end, exactly at it, one millisecond before the grace end, exactly at it.
 * Grace is a seven-day fixture only (AD-7 is open).
 */
class EffectiveStateTest {

    private static final Instant PERIOD_END = Instant.parse("2026-12-01T10:00:00Z");
    private static final GraceSettings GRACE = GraceSettings.ofDays(7); // fixture only (AD-7 open)
    private static final Instant GRACE_END = PERIOD_END.plus(Duration.ofDays(7));
    private static final Instant BEFORE_END = PERIOD_END.minusMillis(1);
    private static final Instant BEFORE_GRACE_END = GRACE_END.minusMillis(1);

    private static EffectiveView eval(LifecycleStatus status, SubscriptionSource source, Instant periodEnd, boolean openEnded,
                                      boolean periodEndEnabled, Instant now) {
        return EffectiveState.of(new SubscriptionSnapshot(status, source, periodEnd, openEnded), GRACE, periodEndEnabled, false, now);
    }

    private static void assertView(EffectiveView v, LifecycleStatus status, boolean entitled, String reason) {
        assertThat(v.status()).isEqualTo(status);
        assertThat(v.entitledToPlan()).isEqualTo(entitled);
        assertThat(v.reasonCode()).isEqualTo(reason);
    }

    @Nested
    class Trial {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void aRunningTrialIsATrialUntilJustBeforeItsEnd(boolean flag) {
            assertView(eval(TRIAL, SubscriptionSource.TRIAL, PERIOD_END, false, flag, BEFORE_END), TRIAL, true, "TRIAL_RUNNING");
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void aTrialEndsExactlyAtItsEndWithoutGraceAndRegardlessOfTheFlag(boolean flag) {
            assertView(eval(TRIAL, SubscriptionSource.TRIAL, PERIOD_END, false, flag, PERIOD_END), EXPIRED, false, "TRIAL_ENDED");
            assertView(eval(TRIAL, SubscriptionSource.TRIAL, PERIOD_END, false, flag, BEFORE_GRACE_END), EXPIRED, false, "TRIAL_ENDED");
            assertThat(eval(TRIAL, SubscriptionSource.TRIAL, PERIOD_END, false, flag, PERIOD_END).graceUntil()).isNull();
        }

        @Test
        void anEndedTrialStaysEnded() {
            assertView(eval(EXPIRED, SubscriptionSource.TRIAL, PERIOD_END, false, true, BEFORE_END), EXPIRED, false, "STORED_EXPIRED");
        }

        @Test
        void grandfatheringNeverAppliesToATrial() {
            // E4-M6: GrandfatheringService.assess excludes trials; a caller passing "ended" can't end a running trial
            EffectiveView v = EffectiveState.of(new SubscriptionSnapshot(TRIAL, SubscriptionSource.TRIAL, PERIOD_END, false),
                    GRACE, true, true, BEFORE_END);
            assertView(v, TRIAL, true, "TRIAL_RUNNING");
        }
    }

    @Nested
    class ManualAndOwner {

        @ParameterizedTest
        @EnumSource(value = SubscriptionSource.class, names = {"MANUAL", "OWNER"})
        void withTheFlagOffAnEndDateDoesNotEndTheRow(SubscriptionSource source) {
            // today's behaviour (W1): non-trial rows are not ended by time
            for (Instant now : new Instant[]{BEFORE_END, PERIOD_END, GRACE_END}) {
                assertView(eval(ACTIVE, source, PERIOD_END, false, false, now), ACTIVE, true, "STORED");
            }
        }

        @ParameterizedTest
        @EnumSource(value = SubscriptionSource.class, names = {"MANUAL", "OWNER"})
        void withTheFlagOnAnExplicitEndDateEndsTheRowExactlyAtItWithoutGrace(SubscriptionSource source) {
            assertView(eval(ACTIVE, source, PERIOD_END, false, true, BEFORE_END), ACTIVE, true, "STORED");
            assertView(eval(ACTIVE, source, PERIOD_END, false, true, PERIOD_END), EXPIRED, false, "PERIOD_END_PASSED");
            assertView(eval(ACTIVE, source, PERIOD_END, false, true, BEFORE_GRACE_END), EXPIRED, false, "PERIOD_END_PASSED");
            assertThat(eval(ACTIVE, source, PERIOD_END, false, true, PERIOD_END).graceUntil()).isNull();
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void anOpenEndedFreeWithAdsRowNeverEndsByTime(boolean flag) {
            assertView(eval(ACTIVE, OWNER, null, false, flag, GRACE_END.plus(Duration.ofDays(3650))), ACTIVE, true, "STORED");
        }

        @Test
        void aManualRowWithoutAnEndDateStaysActive() {
            assertView(eval(ACTIVE, MANUAL, null, false, true, GRACE_END), ACTIVE, true, "STORED");
        }

        @Test
        void aReviewedOpenEndedManualRowIgnoresAPassedEndDate() {
            assertView(eval(ACTIVE, MANUAL, PERIOD_END, true, true, GRACE_END), ACTIVE, true, "OPEN_ENDED");
        }

        @Test
        void aStoredGrandfatheredValueCountsAsCurrentUntilItsEndDate() {
            assertView(eval(GRANDFATHERED, MANUAL, PERIOD_END, false, true, BEFORE_END), GRANDFATHERED, true, "STORED");
            assertView(eval(GRANDFATHERED, MANUAL, PERIOD_END, false, true, PERIOD_END), EXPIRED, false, "PERIOD_END_PASSED");
        }
    }

    @Nested
    class Provider {

        @Test
        void beforeThePeriodEndTheStoredStatusApplies() {
            assertView(eval(ACTIVE, PROVIDER, PERIOD_END, false, true, BEFORE_END), ACTIVE, true, "STORED");
            // a verified renewal failure may already have stored PAST_DUE before the period end
            assertView(eval(PAST_DUE, PROVIDER, PERIOD_END, false, true, BEFORE_END), PAST_DUE, true, "STORED");
        }

        @Test
        void graceStartsExactlyAtThePeriodEnd() {
            EffectiveView v = eval(ACTIVE, PROVIDER, PERIOD_END, false, true, PERIOD_END);
            assertView(v, PAST_DUE, true, "IN_GRACE");
            assertThat(v.graceUntil()).isEqualTo(GRACE_END);
            assertThat(v.periodEnd()).isEqualTo(PERIOD_END);
        }

        @Test
        void graceLastsUntilJustBeforeItsEnd() {
            assertView(eval(ACTIVE, PROVIDER, PERIOD_END, false, true, BEFORE_GRACE_END), PAST_DUE, true, "IN_GRACE");
            assertView(eval(PAST_DUE, PROVIDER, PERIOD_END, false, true, BEFORE_GRACE_END), PAST_DUE, true, "IN_GRACE");
        }

        @ParameterizedTest
        @EnumSource(value = LifecycleStatus.class, names = {"ACTIVE", "PAST_DUE"})
        void theRowEndsExactlyAtTheGraceEnd(LifecycleStatus stored) {
            assertView(eval(stored, PROVIDER, PERIOD_END, false, true, GRACE_END), EXPIRED, false, "GRACE_ENDED");
        }

        @Test
        void graceIsAnchoredAtThePeriodEndNotAtTheEvaluationTime() {
            // evaluating late (a delayed or lost webhook) never extends grace
            Instant muchLater = GRACE_END.plus(Duration.ofDays(30));
            EffectiveView v = eval(ACTIVE, PROVIDER, PERIOD_END, false, true, muchLater);
            assertView(v, EXPIRED, false, "GRACE_ENDED");
            assertThat(v.graceUntil()).isEqualTo(GRACE_END);
        }

        @Test
        void zeroGraceEndsTheRowAtThePeriodEnd() {
            EffectiveView v = EffectiveState.of(new SubscriptionSnapshot(ACTIVE, PROVIDER, PERIOD_END, false),
                    GraceSettings.ofDays(0), true, false, PERIOD_END);
            assertView(v, EXPIRED, false, "GRACE_ENDED");
        }

        @Test
        void withTheFlagOffTheStoredStatusApplies() {
            assertView(eval(ACTIVE, PROVIDER, PERIOD_END, false, false, GRACE_END), ACTIVE, true, "STORED");
        }

        @Test
        void aProviderRowWithoutAPeriodEndIsNeverEntitledWhileTheRuleIsOn() {
            // cannot exist once V23 enforces payment evidence; fail safe if it ever does
            assertView(eval(ACTIVE, PROVIDER, null, false, true, BEFORE_END), EXPIRED, false, "PROVIDER_WITHOUT_PERIOD");
        }

        @Test
        void theGraceEndIsReportedEvenBeforeThePeriodEnds() {
            assertThat(eval(ACTIVE, PROVIDER, PERIOD_END, false, true, BEFORE_END).graceUntil()).isEqualTo(GRACE_END);
        }
    }

    @Nested
    class StoredEndAndGrandfathering {

        @ParameterizedTest
        @EnumSource(SubscriptionSource.class)
        void aStoredEndIsAlwaysAnEnd(SubscriptionSource source) {
            for (boolean flag : new boolean[]{true, false}) {
                assertView(eval(EXPIRED, source, PERIOD_END, false, flag, BEFORE_END), EXPIRED, false, "STORED_EXPIRED");
            }
        }

        @ParameterizedTest
        @EnumSource(value = SubscriptionSource.class, names = {"MANUAL", "OWNER", "PROVIDER"})
        void anApprovedGrandfatheringEndEndsTheRowAsComputedByTheExistingRules(SubscriptionSource source) {
            // the caller supplies GrandfatheringService.effectivelyEnded (E4-M6, unchanged); the evaluator only applies it
            EffectiveView v = EffectiveState.of(new SubscriptionSnapshot(ACTIVE, source, PERIOD_END, false), GRACE, false, true, BEFORE_END);
            assertView(v, EXPIRED, false, "GRANDFATHERING_ENDED");
        }

        @Test
        void withoutAGrandfatheringEndNothingChanges() {
            EffectiveView v = EffectiveState.of(new SubscriptionSnapshot(ACTIVE, MANUAL, null, false), GRACE, false, false, BEFORE_END);
            assertView(v, ACTIVE, true, "STORED");
        }
    }

    @Nested
    class Inputs {

        @Test
        void theEvaluatorReadsAnExistingEntityWithoutChangingIt() {
            BusinessSubscription s = BusinessSubscription.manual("b1", "MODEL_2", 1, PERIOD_END, PERIOD_END.minus(Duration.ofDays(30)), "admin");
            Integer versionBefore = s.getRowVersion();
            EffectiveView v = EffectiveState.of(s, GRACE, true, false, PERIOD_END);
            assertView(v, EXPIRED, false, "PERIOD_END_PASSED");
            assertThat(s.getStatus()).isEqualTo("ACTIVE");
            assertThat(s.getRowVersion()).isEqualTo(versionBefore);
            assertThat(s.getEndedAt()).isNull();
        }

        @Test
        void requiredArgumentsAreChecked() {
            SubscriptionSnapshot snapshot = new SubscriptionSnapshot(ACTIVE, MANUAL, null, false);
            assertThatThrownBy(() -> EffectiveState.of(snapshot, null, true, false, BEFORE_END)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EffectiveState.of(snapshot, GRACE, true, false, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EffectiveState.of((SubscriptionSnapshot) null, GRACE, true, false, BEFORE_END))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new GraceSettings(Duration.ofDays(-1))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
