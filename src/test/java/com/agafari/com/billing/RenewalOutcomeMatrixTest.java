package com.agafari.com.billing;

import com.agafari.com.billing.port.ProviderCapabilities;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.agafari.com.billing.RenewalOutcome.Action.ALREADY_DONE;
import static com.agafari.com.billing.RenewalOutcome.Action.AUTO_REFUND;
import static com.agafari.com.billing.RenewalOutcome.Action.EXCEPTION;
import static com.agafari.com.billing.RenewalOutcome.Action.EXTEND;
import static com.agafari.com.billing.RenewalOutcome.Action.QUARANTINE;
import static com.agafari.com.billing.RenewalOutcome.Action.RETRY;
import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R5-03 (addendum §10, P-R4): the renewal outcome rows R1–R9, first match wins. Pure: no database, clock or provider. */
class RenewalOutcomeMatrixTest {

    private static final ProviderCapabilities REFUNDS = new ProviderCapabilities(true, true, true, Duration.ofHours(24), true, true);
    private static final ProviderCapabilities NO_REFUNDS = new ProviderCapabilities(true, true, false, Duration.ofHours(24), false, true);

    /** Every check passes: a contiguous renewal of a current row. */
    private static RenewalFacts ok() {
        return new RenewalFacts(true, false, true, true, true, false, true, false, true, true, false, false, REFUNDS, true);
    }

    @Test
    void r9AllChecksPassExtends() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok());
        assertThat(o.action()).isEqualTo(EXTEND);
        assertThat(o.row()).isEqualTo("R9");
        assertThat(o.code()).isNull();
    }

    @Test
    void r1UnknownSubscriptionIsQuarantinedBeforeEverythingElse() {
        RenewalFacts f = ok().withSubscriptionKnown(false).withCustomerMatches(false).withAlreadyApplied(true);
        RenewalOutcome o = RenewalOutcomeMatrix.decide(f);
        assertThat(o.action()).isEqualTo(QUARANTINE);
        assertThat(o.row()).isEqualTo("R1");
        assertThat(o.code()).isEqualTo(RenewalOutcome.UNKNOWN_SUBSCRIPTION);
    }

    @Test
    void r2AnAppliedInvoiceIsDoneEvenIfLaterChecksWouldFail() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withAlreadyApplied(true).withCustomerMatches(false));
        assertThat(o.action()).isEqualTo(ALREADY_DONE);
        assertThat(o.row()).isEqualTo("R2");
    }

    @Test
    void r3CustomerMismatchIsHeldForManualReviewNeverRefundedAutomatically() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withCustomerMatches(false));
        assertThat(o.action()).isEqualTo(EXCEPTION);
        assertThat(o.row()).isEqualTo("R3");
        assertThat(o.code()).isEqualTo(RenewalOutcome.CUSTOMER_MISMATCH);
        assertThat(o.refund()).isEqualTo(RenewalOutcome.Refund.MANUAL_HOLD);
    }

    @Test
    void r4NonQualifyingEvidenceIsHeld() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withEvidenceQualifying(false));
        assertThat(o.row()).isEqualTo("R4");
        assertThat(o.code()).isEqualTo(RenewalOutcome.EVIDENCE_NOT_QUALIFYING);
        assertThat(o.refund()).isEqualTo(RenewalOutcome.Refund.MANUAL_HOLD);
    }

    @Test
    void r5PriceOrAmountMismatchIsHeld() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withPriceAndAmountMatch(false));
        assertThat(o.row()).isEqualTo("R5");
        assertThat(o.code()).isEqualTo(RenewalOutcome.AMOUNT_MISMATCH);
        assertThat(o.refund()).isEqualTo(RenewalOutcome.Refund.MANUAL_HOLD);
    }

    @Test
    void r7ADuplicatePeriodIsRefundedAutomaticallyWhenSafe() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withPeriodTakenByAnotherInvoice(true).withPeriodContiguous(false));
        assertThat(o.action()).isEqualTo(AUTO_REFUND);
        assertThat(o.row()).isEqualTo("R7");
        assertThat(o.code()).isEqualTo(RenewalOutcome.DUPLICATE_PERIOD);
    }

    @Test
    void r7WithoutAConfirmedRefundCapabilityFallsBackToManual() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withPeriodTakenByAnotherInvoice(true).withCapabilities(NO_REFUNDS));
        assertThat(o.action()).isEqualTo(EXCEPTION);
        assertThat(o.code()).isEqualTo(PaymentOutcome.REFUND_REQUIRED_MANUAL);
        assertThat(o.cause()).isEqualTo(RenewalOutcome.DUPLICATE_PERIOD);
    }

    @Test
    void r6ALaterPeriodWaitsForTheMissingOneUntilTheGraceEnd() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withPeriodContiguous(false).withPeriodLaterThanExpected(true));
        assertThat(o.action()).isEqualTo(RETRY);
        assertThat(o.row()).isEqualTo("R6");
        assertThat(o.code()).isEqualTo(RenewalOutcome.PENDING_ORDER);
    }

    @Test
    void r6AfterTheGraceEndTheLaterPeriodBecomesAnException() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withPeriodContiguous(false).withPeriodLaterThanExpected(true).withBeforeGraceEnd(false));
        assertThat(o.action()).isEqualTo(EXCEPTION);
        assertThat(o.code()).isEqualTo(RenewalOutcome.PERIOD_MISMATCH);
        assertThat(o.refund()).isEqualTo(RenewalOutcome.Refund.MANUAL_HOLD);
    }

    @Test
    void r6AnEarlierOrWrongLengthPeriodIsNeverRetried() {
        assertThat(RenewalOutcomeMatrix.decide(ok().withPeriodContiguous(false)).action()).isEqualTo(EXCEPTION);
        RenewalOutcome wrongLength = RenewalOutcomeMatrix.decide(ok().withIntervalMatches(false));
        assertThat(wrongLength.action()).isEqualTo(EXCEPTION);
        assertThat(wrongLength.code()).isEqualTo(RenewalOutcome.PERIOD_MISMATCH);
    }

    @Test
    void r8APaymentAfterTheRowEndedIsNeverAppliedAndRefundedWhenSafe() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withRowEnded(true).withPaidAfterRowEnded(true));
        assertThat(o.action()).isEqualTo(AUTO_REFUND);
        assertThat(o.row()).isEqualTo("R8");
        assertThat(o.code()).isEqualTo(RenewalOutcome.PAID_AFTER_EXPIRY);
        RenewalOutcome manual = RenewalOutcomeMatrix.decide(ok().withRowEnded(true).withPaidAfterRowEnded(true).withCapabilities(NO_REFUNDS));
        assertThat(manual.code()).isEqualTo(PaymentOutcome.REFUND_REQUIRED_MANUAL);
        assertThat(manual.cause()).isEqualTo(RenewalOutcome.PAID_AFTER_EXPIRY);
    }

    @Test
    void r8AnEndedRowPaidInTimeIsHeldNotReactivated() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withRowEnded(true));
        assertThat(o.action()).isEqualTo(EXCEPTION);
        assertThat(o.row()).isEqualTo("R8");
        assertThat(o.code()).isEqualTo(RenewalOutcome.ROW_ENDED);
        assertThat(o.refund()).isEqualTo(RenewalOutcome.Refund.MANUAL_HOLD);
    }

    @Test
    void neverExtendsAnEndedRowWhateverElseHolds() {
        for (boolean after : new boolean[]{true, false}) {
            assertThat(RenewalOutcomeMatrix.decide(ok().withRowEnded(true).withPaidAfterRowEnded(after)).action()).isNotEqualTo(EXTEND);
        }
    }

    @Test
    void anAutomaticRefundIsOnlyEverAFullRefundOfTheVerifiedCharge() {
        RenewalOutcome o = RenewalOutcomeMatrix.decide(ok().withPeriodTakenByAnotherInvoice(true).withFullRefund(false));
        assertThat(o.action()).isEqualTo(EXCEPTION);
        assertThat(o.code()).isEqualTo(PaymentOutcome.REFUND_REQUIRED_MANUAL);
    }
}
