package com.agafari.com.billing;

import com.agafari.com.billing.PaymentOutcome.Action;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.agafari.com.billing.PaymentOutcome.Action.ACTIVATE;
import static com.agafari.com.billing.PaymentOutcome.Action.AUTO_REFUND;
import static com.agafari.com.billing.PaymentOutcome.Action.EXCEPTION;
import static com.agafari.com.billing.PaymentOutcome.Action.EXTEND;
import static com.agafari.com.billing.PaymentOutcome.Action.REPLACE;
import static com.agafari.com.billing.PaymentOutcome.Action.UNRESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R4-08: the table equals TA §6.3, one test per row, with every boundary. */
class PaymentOutcomeMatrixTest {

    private static final Instant T = Instant.parse("2030-06-01T12:00:00Z");
    private static final ProviderCapabilities SAFE = new ProviderCapabilities(true, true, true, Duration.ofHours(24), true);
    private static final ProviderCapabilities NOT_SAFE = new ProviderCapabilities(true, true, false, Duration.ofHours(24), true);

    /** Everything fine: row 7, a new purchase. */
    private static F f() {
        return new F(PaymentVerification.VERIFIED, true, false, T, T.plusSeconds(3600), null, true, null, null, CheckoutKind.NEW, SAFE, true);
    }

    private record F(PaymentVerification v, boolean known, boolean other, Instant pay, Instant exp, Instant cancel, boolean eligible, String replaces, String current, CheckoutKind kind, ProviderCapabilities caps, boolean full) {
        OutcomeFacts facts() {
            return new OutcomeFacts(v, known, other, pay, exp, cancel, eligible, replaces, current, kind, caps, full);
        }

        PaymentOutcome decide() {
            return PaymentOutcomeMatrix.decide(facts());
        }

        F v(PaymentVerification x) { return new F(x, known, other, pay, exp, cancel, eligible, replaces, current, kind, caps, full); }
        F unknown() { return new F(v, false, other, pay, exp, cancel, eligible, replaces, current, kind, caps, full); }
        F completed() { return new F(v, known, true, pay, exp, cancel, eligible, replaces, current, kind, caps, full); }
        F exp(Instant x) { return new F(v, known, other, pay, x, cancel, eligible, replaces, current, kind, caps, full); }
        F cancel(Instant x) { return new F(v, known, other, pay, exp, x, eligible, replaces, current, kind, caps, full); }
        F ineligible() { return new F(v, known, other, pay, exp, cancel, false, replaces, current, kind, caps, full); }
        F changed() { return new F(v, known, other, pay, exp, cancel, eligible, "expected", "other", kind, caps, full); }
        F same(String id) { return new F(v, known, other, pay, exp, cancel, eligible, id, id, kind, caps, full); }
        F kind(CheckoutKind x) { return new F(v, known, other, pay, exp, cancel, eligible, replaces, current, x, caps, full); }
        F caps(ProviderCapabilities x) { return new F(v, known, other, pay, exp, cancel, eligible, replaces, current, kind, x, full); }
        F partial() { return new F(v, known, other, pay, exp, cancel, eligible, replaces, current, kind, caps, false); }
    }

    private static void is(PaymentOutcome o, Action action, int row, String code, String cause) {
        assertThat(o).isEqualTo(new PaymentOutcome(action, row, code, cause));
    }

    // ---- AC1: one test per row

    @Test
    void row0AFailedVerificationIsAnExceptionAndNeverARefund() {
        is(f().v(PaymentVerification.FAILED).decide(), EXCEPTION, 0, "VERIFICATION_FAILED", "VERIFICATION_FAILED");
    }

    @Test
    void row1AnUnknownSessionIsAnException() {
        is(f().unknown().decide(), EXCEPTION, 1, "UNKNOWN_REFERENCE", "UNKNOWN_REFERENCE");
    }

    static Stream<Arguments> refundRows() {
        return Stream.of(Arguments.of(2, "DUPLICATE_PAYMENT", f().completed()), Arguments.of(3, "LATE_PAYMENT", f().exp(T.minusSeconds(1))),
                Arguments.of(4, "PAID_AFTER_CANCEL", f().cancel(T.minusSeconds(1))), Arguments.of(5, "PLAN_NOT_ELIGIBLE", f().ineligible()));
    }

    @ParameterizedTest(name = "row {0}")
    @MethodSource("refundRows")
    void rows2To5RefundAutomaticallyWhenSafe(int row, String code, F given) {
        is(given.decide(), AUTO_REFUND, row, code, code);
    }

    @ParameterizedTest(name = "row {0}")
    @MethodSource("refundRows")
    void ac3WithoutTheCapabilityThereIsNoAutomaticRefund(int row, String code, F given) {
        is(given.caps(NOT_SAFE).decide(), EXCEPTION, row, "REFUND_REQUIRED_MANUAL", code);
    }

    @ParameterizedTest(name = "row {0}")
    @MethodSource("refundRows")
    void ac3WithNoCapabilitiesAtAllThereIsNoAutomaticRefund(int row, String code, F given) {
        is(given.caps(ProviderCapabilities.none()).decide(), EXCEPTION, row, "REFUND_REQUIRED_MANUAL", code);
    }

    @ParameterizedTest(name = "row {0}")
    @MethodSource("refundRows")
    void anAutomaticRefundIsOnlyEverAFullRefundOfAVerifiedCharge(int row, String code, F given) {
        is(given.partial().decide(), EXCEPTION, row, "REFUND_REQUIRED_MANUAL", code);
    }

    @Test
    void row6IsAnExceptionWithNoActivationAndNoRefund() {
        PaymentOutcome o = f().changed().decide();
        is(o, EXCEPTION, 6, "STATE_CHANGED", "STATE_CHANGED");
        assertThat(o.action()).isNotIn(ACTIVATE, EXTEND, REPLACE, AUTO_REFUND);
    }

    @Test
    void row6DoesNotDependOnRefundCapability() {
        is(f().changed().caps(NOT_SAFE).decide(), EXCEPTION, 6, "STATE_CHANGED", "STATE_CHANGED");
        is(f().changed().partial().decide(), EXCEPTION, 6, "STATE_CHANGED", "STATE_CHANGED");
    }

    @Test
    void row6AppliesWhenTheExpectedSubscriptionHasGone() {
        F gone = new F(PaymentVerification.VERIFIED, true, false, T, T.plusSeconds(1), null, true, "expected", null, CheckoutKind.RENEWAL, SAFE, true);
        is(gone.decide(), EXCEPTION, 6, "STATE_CHANGED", "STATE_CHANGED");
    }

    @Test
    void row6AppliesWhenASubscriptionAppearedUnexpectedly() {
        F appeared = new F(PaymentVerification.VERIFIED, true, false, T, T.plusSeconds(1), null, true, null, "someone", CheckoutKind.NEW, SAFE, true);
        is(appeared.decide(), EXCEPTION, 6, "STATE_CHANGED", "STATE_CHANGED");
    }

    @Test
    void row7ANewPurchaseActivates() {
        is(f().decide(), ACTIVATE, 7, null, null);
    }

    @Test
    void row7ARenewalExtends() {
        is(f().same("s1").kind(CheckoutKind.RENEWAL).decide(), EXTEND, 7, null, null);
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutKind.class, names = {"CONVERSION", "UPGRADE", "DOWNGRADE_RENEWAL"})
    void row7AChangeOfSubscriptionReplaces(CheckoutKind kind) {
        is(f().same("s1").kind(kind).decide(), REPLACE, 7, null, null);
    }

    // ---- PO D2

    @Test
    void anUnresolvedOutcomeIsNotAVerificationFailureAndDoesNothing() {
        PaymentOutcome o = f().v(PaymentVerification.UNRESOLVED).decide();
        is(o, UNRESOLVED, -1, null, null);
        assertThat(o.code()).isNotEqualTo("VERIFICATION_FAILED");
    }

    @Test
    void anUnresolvedOutcomeOutranksEveryOtherFact() {
        F worst = new F(PaymentVerification.UNRESOLVED, false, true, null, null, T, false, "a", "b", null, null, false);
        assertThat(worst.decide().action()).isEqualTo(UNRESOLVED);
    }

    // ---- AC2: boundaries

    @Test
    void ac2PaymentExactlyAtExpiryIsHonored() {
        is(f().exp(T).decide(), ACTIVATE, 7, null, null);
    }

    @Test
    void oneNanosecondAfterExpiryIsLate() {
        assertThat(f().exp(T.minusNanos(1)).decide().row()).isEqualTo(3);
    }

    @Test
    void ac2PaymentExactlyAtTheCancelRequestIsHonored() {
        is(f().cancel(T).decide(), ACTIVATE, 7, null, null);
    }

    @Test
    void oneNanosecondAfterTheCancelRequestIsAfterCancel() {
        assertThat(f().cancel(T.minusNanos(1)).decide().row()).isEqualTo(4);
    }

    @Test
    void paymentBeforeTheCancelRequestIsHonored() {
        is(f().cancel(T.plusSeconds(1)).decide(), ACTIVATE, 7, null, null);
    }

    // ---- first match wins

    static Stream<Arguments> precedence() {
        F everything = f().completed().exp(T.minusSeconds(1)).cancel(T.minusSeconds(1)).ineligible().changed();
        return Stream.of(Arguments.of(0, everything.v(PaymentVerification.FAILED).unknown()), Arguments.of(1, everything.unknown()), Arguments.of(2, everything),
                Arguments.of(3, f().exp(T.minusSeconds(1)).cancel(T.minusSeconds(1)).ineligible().changed()), Arguments.of(4, f().cancel(T.minusSeconds(1)).ineligible().changed()),
                Arguments.of(5, f().ineligible().changed()), Arguments.of(6, f().changed()));
    }

    @ParameterizedTest(name = "row {0}")
    @MethodSource("precedence")
    void theFirstMatchingRowWins(int row, F given) {
        assertThat(given.decide().row()).isEqualTo(row);
    }

    @Test
    void row0NeverReadsTheFactsItCantTrust() {
        F bare = new F(PaymentVerification.FAILED, false, false, null, null, null, false, null, null, null, null, false);
        assertThat(bare.decide().row()).isZero();
    }

    // ---- AC4: eligibility at the payment time (PD-2a)

    private static final Instant DISABLED_AT = Instant.parse("2030-06-01T00:00:00Z");
    private static final List<ConfigVersionFacts> HISTORY = List.of(new ConfigVersionFacts(1, DISABLED_AT.minusSeconds(86400), Set.of("MODEL_3", "MODEL_4")), new ConfigVersionFacts(2, DISABLED_AT, Set.of("MODEL_3")));

    @Test
    void ac4APaymentBeforeTheDisableIsEligible() {
        assertThat(EligibilityAtTime.eligible(HISTORY, "MODEL_4", DISABLED_AT.minusNanos(1))).isTrue();
    }

    @Test
    void ac4APaymentAfterTheDisableIsNot() {
        assertThat(EligibilityAtTime.eligible(HISTORY, "MODEL_4", DISABLED_AT.plusNanos(1))).isFalse();
    }

    @Test
    void aPaymentAtTheExactChangeFollowsTheNewVersion() {
        assertThat(EligibilityAtTime.eligible(HISTORY, "MODEL_4", DISABLED_AT)).isFalse();
        assertThat(EligibilityAtTime.eligible(HISTORY, "MODEL_3", DISABLED_AT)).isTrue();
    }

    @Test
    void aPlanEnabledLaterIsNotEligibleEarlier() {
        List<ConfigVersionFacts> enabledLater = List.of(new ConfigVersionFacts(1, DISABLED_AT.minusSeconds(100), Set.of("MODEL_3")), new ConfigVersionFacts(2, DISABLED_AT, Set.of("MODEL_3", "MODEL_4")));
        assertThat(EligibilityAtTime.eligible(enabledLater, "MODEL_4", DISABLED_AT.minusSeconds(1))).isFalse();
        assertThat(EligibilityAtTime.eligible(enabledLater, "MODEL_4", DISABLED_AT)).isTrue();
    }

    @Test
    void historyOrderDoesNotMatter() {
        assertThat(EligibilityAtTime.eligible(HISTORY.reversed(), "MODEL_4", DISABLED_AT.plusSeconds(1))).isFalse();
    }

    @Test
    void theHigherVersionWinsAtTheSameInstant() {
        List<ConfigVersionFacts> tie = List.of(new ConfigVersionFacts(2, DISABLED_AT, Set.of("MODEL_4")), new ConfigVersionFacts(3, DISABLED_AT, Set.of("MODEL_3")));
        assertThat(EligibilityAtTime.eligible(tie, "MODEL_4", DISABLED_AT)).isFalse();
        assertThat(EligibilityAtTime.eligible(tie.reversed(), "MODEL_3", DISABLED_AT)).isTrue();
    }

    @Test
    void beforeAnyConfigurationNothingIsEligible() {
        assertThat(EligibilityAtTime.eligible(HISTORY, "MODEL_4", DISABLED_AT.minusSeconds(999999))).isFalse();
        assertThat(EligibilityAtTime.eligible(List.of(), "MODEL_4", T)).isFalse();
    }

    @Test
    void anUnknownPlanIsNotEligible() {
        assertThat(EligibilityAtTime.eligible(HISTORY, "MODEL_9", DISABLED_AT.plusSeconds(1))).isFalse();
    }

    @Test
    void aConfigurationsPlanSetIsImmutable() {
        java.util.Set<String> source = new java.util.HashSet<>(Set.of("MODEL_4"));
        ConfigVersionFacts facts = new ConfigVersionFacts(1, T, source);
        source.clear();
        assertThat(facts.enabledPlanCodes()).containsExactly("MODEL_4");
    }
}
