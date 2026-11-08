package com.agafari.com.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.agafari.com.subscription.LifecycleStatus.ACTIVE;
import static com.agafari.com.subscription.LifecycleStatus.EXPIRED;
import static com.agafari.com.subscription.LifecycleStatus.GRANDFATHERED;
import static com.agafari.com.subscription.LifecycleStatus.PAST_DUE;
import static com.agafari.com.subscription.LifecycleStatus.TRIAL;
import static com.agafari.com.subscription.SubscriptionSource.MANUAL;
import static com.agafari.com.subscription.SubscriptionSource.OWNER;
import static com.agafari.com.subscription.SubscriptionSource.PROVIDER;
import static com.agafari.com.subscription.TransitionReason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R2-01: the lifecycle transition table (Backend Detailed Design Rev. 2 §2.2, TA Rev. 3 §3.1, §4.1–4.2).
 * The expected table below is written independently of the production table, row by row, so any change to either side
 * is visible. Every (from, to, reason, source) combination is checked against it.
 */
class TransitionRulesTest {

    /** The approved table: from (null = new row), to, reason, sources. */
    private static final List<Transition> EXPECTED = List.of(
            // creation of a new row
            new Transition(null, TRIAL, REGISTRATION_TRIAL, Set.of(SubscriptionSource.TRIAL)),
            new Transition(null, ACTIVE, OWNER_FREE, Set.of(OWNER)),
            new Transition(null, ACTIVE, ADMIN_RECORD, Set.of(MANUAL)),
            new Transition(null, ACTIVE, VERIFIED_PAYMENT, Set.of(PROVIDER)),
            // same row, same contract
            new Transition(ACTIVE, ACTIVE, VERIFIED_RENEWAL, Set.of(PROVIDER)),
            new Transition(ACTIVE, ACTIVE, ADMIN_PERIOD_CHANGE, Set.of(MANUAL, OWNER)),
            new Transition(TRIAL, TRIAL, ADMIN_PERIOD_CHANGE, Set.of(SubscriptionSource.TRIAL)),
            new Transition(ACTIVE, PAST_DUE, RENEWAL_FAILED, Set.of(PROVIDER)),
            new Transition(PAST_DUE, ACTIVE, VERIFIED_RENEWAL, Set.of(PROVIDER)),
            // endings
            new Transition(TRIAL, EXPIRED, TRIAL_END, Set.of(SubscriptionSource.TRIAL)),
            new Transition(TRIAL, EXPIRED, CONVERTED, Set.of(SubscriptionSource.TRIAL)),
            new Transition(TRIAL, EXPIRED, ADMIN, Set.of(SubscriptionSource.TRIAL)),
            new Transition(ACTIVE, EXPIRED, CANCELLED, Set.of(PROVIDER)),
            new Transition(ACTIVE, EXPIRED, CHANGED, Set.of(MANUAL, OWNER, PROVIDER)),
            new Transition(ACTIVE, EXPIRED, PERIOD_END, Set.of(MANUAL, OWNER)),
            new Transition(ACTIVE, EXPIRED, ADMIN, Set.of(MANUAL, OWNER, PROVIDER)),
            new Transition(ACTIVE, EXPIRED, GRANDFATHERED_END, Set.of(MANUAL, OWNER, PROVIDER)),
            new Transition(ACTIVE, EXPIRED, REFUNDED, Set.of(PROVIDER)),
            new Transition(ACTIVE, EXPIRED, DISPUTE_LOST, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, PAYMENT_FAILED, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, ADMIN, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, REFUNDED, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, DISPUTE_LOST, Set.of(PROVIDER)),
            new Transition(GRANDFATHERED, EXPIRED, GRANDFATHERED_END, Set.of(MANUAL, OWNER, PROVIDER)),
            new Transition(GRANDFATHERED, EXPIRED, ADMIN, Set.of(MANUAL, OWNER, PROVIDER)));

    @Test
    void theProductionTableIsExactlyTheApprovedTable() {
        assertThat(TransitionRules.table()).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void everyCombinationIsAllowedIfAndOnlyIfTheApprovedTableListsIt() {
        List<LifecycleStatus> froms = new ArrayList<>();
        froms.add(null);
        froms.addAll(EnumSet.allOf(LifecycleStatus.class));
        int checked = 0;
        for (LifecycleStatus from : froms) {
            for (LifecycleStatus to : LifecycleStatus.values()) {
                for (TransitionReason reason : TransitionReason.values()) {
                    for (SubscriptionSource source : SubscriptionSource.values()) {
                        boolean expected = EXPECTED.stream().anyMatch(t -> t.from() == from && t.to() == to
                                && t.reason() == reason && t.sources().contains(source));
                        assertThat(TransitionRules.isAllowed(from, to, reason, source))
                                .as("%s -> %s (%s, %s)", from, to, reason, source).isEqualTo(expected);
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(6 * 5 * TransitionReason.values().length * 4);
    }

    @ParameterizedTest
    @EnumSource(TransitionReason.class)
    void aTrialNeverBecomesActiveOnTheSameRow(TransitionReason reason) {
        for (SubscriptionSource source : SubscriptionSource.values()) {
            assertThat(TransitionRules.isAllowed(TRIAL, ACTIVE, reason, source)).as("%s %s", reason, source).isFalse();
            assertThatThrownBy(() -> TransitionRules.require(TRIAL, ACTIVE, reason, source))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(LifecycleStatus.class)
    void anEndedRowNeverChanges(LifecycleStatus to) {
        for (TransitionReason reason : TransitionReason.values()) {
            for (SubscriptionSource source : SubscriptionSource.values()) {
                assertThat(TransitionRules.isAllowed(EXPIRED, to, reason, source)).isFalse();
            }
        }
    }

    @Test
    void nothingCanReturnToTrialOrStartAsPastDue() {
        for (LifecycleStatus from : LifecycleStatus.values()) {
            if (from == TRIAL) continue;
            for (TransitionReason reason : TransitionReason.values()) {
                for (SubscriptionSource source : SubscriptionSource.values()) {
                    assertThat(TransitionRules.isAllowed(from, TRIAL, reason, source)).isFalse();
                    assertThat(TransitionRules.isAllowed(null, PAST_DUE, reason, source)).isFalse();
                }
            }
        }
    }

    @Test
    void theStatusChangesOfExistingRowsMatchTheV20GuardTable() {
        // TA Rev. 3 §3.1: ACTIVE -> PAST_DUE, PAST_DUE -> ACTIVE, {TRIAL, ACTIVE, PAST_DUE, GRANDFATHERED} -> EXPIRED; nothing else
        Set<List<LifecycleStatus>> v20 = new HashSet<>(List.of(List.of(ACTIVE, PAST_DUE), List.of(PAST_DUE, ACTIVE),
                List.of(TRIAL, EXPIRED), List.of(ACTIVE, EXPIRED), List.of(PAST_DUE, EXPIRED), List.of(GRANDFATHERED, EXPIRED)));
        assertThat(TransitionRules.statusChangesOfExistingRows()).isEqualTo(v20);
    }

    @Test
    void endingsThatNeedASuccessorAreRefusedOnTheirOwn() {
        assertThatThrownBy(() -> TransitionRules.require(TRIAL, EXPIRED, CONVERTED, SubscriptionSource.TRIAL))
                .isInstanceOf(IllegalTransitionException.class).hasMessageContaining("successor");
        assertThatThrownBy(() -> TransitionRules.require(ACTIVE, EXPIRED, CHANGED, OWNER))
                .isInstanceOf(IllegalTransitionException.class).hasMessageContaining("successor");
    }

    @Test
    void aReplacementEndsTheOldRowAndCreatesANewOne() {
        // trial conversion (today: admin conversion to MANUAL; target: verified payment to PROVIDER)
        assertThatCode(() -> TransitionRules.requireReplacement(TRIAL, SubscriptionSource.TRIAL, CONVERTED, ADMIN_RECORD, MANUAL))
                .doesNotThrowAnyException();
        assertThatCode(() -> TransitionRules.requireReplacement(TRIAL, SubscriptionSource.TRIAL, CONVERTED, VERIFIED_PAYMENT, PROVIDER))
                .doesNotThrowAnyException();
        // Free with Ads upgraded by a verified payment
        assertThatCode(() -> TransitionRules.requireReplacement(ACTIVE, OWNER, CHANGED, VERIFIED_PAYMENT, PROVIDER))
                .doesNotThrowAnyException();
        // a paid row downgraded to Free with Ads at period end
        assertThatCode(() -> TransitionRules.requireReplacement(ACTIVE, PROVIDER, CHANGED, OWNER_FREE, OWNER))
                .doesNotThrowAnyException();
    }

    @Test
    void aReplacementIsRefusedWhenEitherHalfIsNotPermitted() {
        // the ending reason doesn't take a successor
        assertThatThrownBy(() -> TransitionRules.requireReplacement(ACTIVE, MANUAL, ADMIN, ADMIN_RECORD, MANUAL))
                .isInstanceOf(IllegalTransitionException.class);
        // the "successor" is not a creation
        assertThatThrownBy(() -> TransitionRules.requireReplacement(ACTIVE, OWNER, CHANGED, VERIFIED_RENEWAL, PROVIDER))
                .isInstanceOf(IllegalTransitionException.class);
        // the successor's creation reason doesn't match its source
        assertThatThrownBy(() -> TransitionRules.requireReplacement(ACTIVE, OWNER, CHANGED, VERIFIED_PAYMENT, MANUAL))
                .isInstanceOf(IllegalTransitionException.class);
        // the ending doesn't match the old row's source
        assertThatThrownBy(() -> TransitionRules.requireReplacement(ACTIVE, MANUAL, CONVERTED, VERIFIED_PAYMENT, PROVIDER))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void requireAcceptsEveryPermittedStepThatNeedsNoSuccessor() {
        for (Transition t : EXPECTED) {
            if (t.reason().requiresSuccessor()) continue;
            for (SubscriptionSource source : t.sources()) {
                assertThatCode(() -> TransitionRules.require(t.from(), t.to(), t.reason(), source)).as(t.toString())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void adminPeriodChangesStayAvailableForManualTrialAndOwnerButNeverForProviderRows() {
        // R-4: the existing Super Admin end-date edits; PROVIDER periods move only with verified evidence
        assertThat(TransitionRules.isAllowed(ACTIVE, ACTIVE, ADMIN_PERIOD_CHANGE, MANUAL)).isTrue();
        assertThat(TransitionRules.isAllowed(ACTIVE, ACTIVE, ADMIN_PERIOD_CHANGE, OWNER)).isTrue();
        assertThat(TransitionRules.isAllowed(TRIAL, TRIAL, ADMIN_PERIOD_CHANGE, SubscriptionSource.TRIAL)).isTrue();
        assertThat(TransitionRules.isAllowed(ACTIVE, ACTIVE, ADMIN_PERIOD_CHANGE, PROVIDER)).isFalse();
        assertThat(TransitionRules.isAllowed(ACTIVE, ACTIVE, VERIFIED_RENEWAL, MANUAL)).isFalse();
    }

    @Test
    void everyEndingReasonEndsTheRowAndOnlyConversionAndChangeTakeASuccessor() {
        for (Transition t : EXPECTED) {
            assertThat(t.reason().isEnding()).as(t.toString()).isEqualTo(t.to() == EXPIRED);
        }
        assertThat(EnumSet.allOf(TransitionReason.class).stream().filter(TransitionReason::requiresSuccessor))
                .containsExactlyInAnyOrder(CONVERTED, CHANGED);
    }
}
