package com.agafari.com.billing;

import com.agafari.com.billing.PaymentOutcomeService.Result;
import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.support.MonetizationFixtures;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-10: every matrix row end to end with the fake provider, on PostgreSQL (AC1–AC6). */
class PaymentOutcomeServiceTest extends PaymentOutcomeTestBase {

    private static final String VERIFIED = "SIGNED_WEBHOOK+API_FETCH";

    private Instant soon() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    // ---- row 7 ----

    @Test
    void row7NewActivatesAPaidSubscriptionFromVerifiedEvidence() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        Instant tPay = soon();
        String e = event(paid(c, tPay));

        assertThat(process(e)).isEqualTo(Result.PROCESSED);

        Map<String, Object> s = jdbc.queryForMap("select * from business_subscription where business_id = ?", owner.businessId());
        assertThat(s.get("source")).isEqualTo("PROVIDER");
        assertThat(s.get("status")).isEqualTo("ACTIVE");
        assertThat(s.get("plan_code")).isEqualTo("MODEL_4");
        assertThat(s.get("provider")).isEqualTo(PROVIDER);
        assertThat(s.get("price_id")).isEqualTo(price);
        assertThat(s.get("grace_days")).as("AD-7 is open: no grace is invented").isNull();
        assertThat(((Timestamp) s.get("period_end")).toInstant()).isEqualTo(oneMonth(tPay));

        Map<String, Object> pay = jdbc.queryForMap("select * from payment where business_id = ?", owner.businessId());
        assertThat(pay.get("kind")).isEqualTo("CHARGE");
        assertThat(pay.get("status")).isEqualTo("SUCCEEDED");
        assertThat(pay.get("verification")).isEqualTo(VERIFIED);
        assertThat(pay.get("verified_at")).isNotNull();
        assertThat(((Timestamp) pay.get("occurred_at")).toInstant()).isEqualTo(tPay);
        assertThat(pay.get("checkout_session_id")).isEqualTo(c);
        assertThat(pay.get("amount_minor")).isEqualTo(ET_AMOUNT);

        Map<String, Object> inv = jdbc.queryForMap("select * from billing_invoice where business_id = ?", owner.businessId());
        assertThat(inv.get("status")).isEqualTo("PAID");
        assertThat(inv.get("id")).isEqualTo(pay.get("invoice_id"));
        assertThat(((Timestamp) inv.get("period_start")).toInstant()).as("R8: an initial period starts at t_pay").isEqualTo(tPay);

        Map<String, Object> period = jdbc.queryForMap("select * from subscription_period where business_id = ?", owner.businessId());
        assertThat(period.get("payment_id")).isEqualTo(pay.get("id"));
        assertThat(period.get("subscription_id")).isEqualTo(s.get("id"));

        assertThat(checkoutStatus(c)).isEqualTo("COMPLETED");
        assertThat(eventRow(e).get("status")).isEqualTo("PROCESSED");
        assertThat(eventRow(e).get("processed_at")).isNotNull();
        assertThat(exceptions(owner)).isZero();
        assertThat(refunds(owner)).isZero();
        assertThat(fakeEt.verifyRanInTransaction()).isFalse();
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void row7RenewalExtendsFromTheCurrentPeriodEnd() {
        Instant first = soon();
        String sub = activate(owner, first);
        String c = checkout(owner, CheckoutKind.RENEWAL, sub);

        assertThat(payAndProcess(c, soon().plusSeconds(5))).isEqualTo(Result.PROCESSED);

        Map<String, Object> s = jdbc.queryForMap("select * from business_subscription where id = ?", sub);
        assertThat(((Timestamp) s.get("period_end")).toInstant()).as("PD-3: a renewal starts at period_end").isEqualTo(oneMonth(oneMonth(first)));
        assertThat(periods(owner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select min(period_start) from subscription_period where business_id = ? and period_start > ?", Timestamp.class, owner.businessId(), Timestamp.from(first)).toInstant())
                .isEqualTo(oneMonth(first));
        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void row7UpgradeReplacesTheCurrentPaidRow() {
        String sub = activate(owner, soon());
        String c = checkout(owner, CheckoutKind.UPGRADE, sub, otherPrice, 700, Instant.now().plus(23, ChronoUnit.HOURS));
        Instant tPay = soon().plusSeconds(5);

        assertThat(payAndProcess(c, tPay)).isEqualTo(Result.PROCESSED);

        assertThat(jdbc.queryForMap("select status, ended_reason from business_subscription where id = ?", sub)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED");
        Map<String, Object> next = jdbc.queryForMap("select * from business_subscription where business_id = ? and status = 'ACTIVE'", owner.businessId());
        assertThat(next.get("plan_code")).isEqualTo("MODEL_2");
        assertThat(((Timestamp) next.get("period_end")).toInstant()).as("R8: a replacement starts at t_pay").isEqualTo(oneMonth(tPay));
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void row7ConversionReplacesATrial() {
        startTrial(owner, Instant.now().plus(10, ChronoUnit.DAYS));
        String trial = current(owner);
        String c = checkout(owner, CheckoutKind.CONVERSION, trial);

        assertThat(payAndProcess(c, soon())).isEqualTo(Result.PROCESSED);

        assertThat(jdbc.queryForMap("select status, ended_reason from business_subscription where id = ?", trial)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CONVERTED");
        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    // ---- row 0 ----

    @ParameterizedTest
    @ValueSource(strings = {"amount", "currency"})
    void row0AVerifiedAmountOrCurrencyThatDiffersIsAnExceptionWithoutALedgerCharge(String what) {
        String c = checkout(owner, CheckoutKind.NEW, null);
        Money wrong = what.equals("amount") ? new Money(ET_AMOUNT - 1, Currency.ETB) : new Money(ET_AMOUNT, Currency.USD);
        String e = event(paid(c, soon(), wrong, metadata(c)));

        assertThat(process(e)).isEqualTo(Result.PROCESSED);

        Map<String, Object> x = onlyException(owner);
        assertThat(x.get("code")).isEqualTo(PaymentOutcome.VERIFICATION_FAILED);
        assertThat(x.get("status")).isEqualTo("OPEN");
        assertThat(x.get("payment_id")).isNull();
        assertThat(x.get("provider_event_id")).isEqualTo(e);
        assertThat(x.get("checkout_id")).isEqualTo(c);
        assertThat(x.get("involves_customer_funds")).isEqualTo(true);
        assertThat(payments(owner)).isZero();
        assertThat(refunds(owner)).as("row 0: no refund").isZero();
        assertThat(current(owner)).isNull();
        assertThat(checkoutStatus(c)).isEqualTo("OPEN");
        assertThat(eventRow(e).get("status")).isEqualTo("PROCESSED");
    }

    // ---- row 1: quarantine ----

    @ParameterizedTest
    @ValueSource(strings = {"no-metadata", "unknown-checkout", "other-restaurants-checkout", "other-restaurant-named", "session-ref-conflict", "session-ref-missing",
            "checkout-of-another-provider", "refetch-names-another-payment"})
    void row1MissingOrConflictingEvidenceIsQuarantinedAndOwnershipIsNeverInferred(String variant) {
        Tenant other = data.tenant("outcome-other");
        String mine = checkout(owner, CheckoutKind.NEW, null);
        String theirs = checkout(other, CheckoutKind.NEW, null);
        Map<String, String> m = new HashMap<>(metadata(mine));
        String ref = "pay_q_" + variant;
        VerifiedPayment answer = null;
        switch (variant) {
            case "no-metadata" -> m.clear();
            case "unknown-checkout" -> m.put(PaymentMetadata.CHECKOUT_ID, "00000000-0000-0000-0000-000000000000");
            case "other-restaurants-checkout" -> m.put(PaymentMetadata.CHECKOUT_ID, theirs);
            case "other-restaurant-named" -> m.put(PaymentMetadata.BUSINESS_ID, other.businessId());
            case "session-ref-conflict" -> m.put(PaymentMetadata.PROVIDER_SESSION_REF, "sess_somebody_else");
            case "session-ref-missing" -> m.remove(PaymentMetadata.PROVIDER_SESSION_REF);
            case "checkout-of-another-provider" -> jdbc.update("update checkout_session set provider = 'FAKE_US' where id = ?", mine);
            case "refetch-names-another-payment" -> answer = new VerifiedPayment("pay_someone_else", ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), soon(), m);
            default -> throw new IllegalArgumentException(variant);
        }
        fakeEt.payment(ref, answer != null ? answer : new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), soon(), m));
        String e = event(ref);

        assertThat(process(e)).isEqualTo(Result.QUARANTINED);

        assertThat(eventRow(e).get("status")).isEqualTo("QUARANTINED");
        assertThat((String) eventRow(e).get("last_error")).isNotBlank();
        assertThat(count("select count(*) from payment")).isZero();
        assertThat(count("select count(*) from billing_invoice")).isZero();
        assertThat(count("select count(*) from billing_exception")).isZero();
        assertThat(count("select count(*) from refund_request")).isZero();
        assertThat(count("select count(*) from business_subscription")).isZero();
        assertThat(checkoutStatus(mine)).isEqualTo("OPEN");
        assertThat(checkoutStatus(theirs)).isEqualTo("OPEN");
        assertThat(process(e)).as("a quarantined event is final").isEqualTo(Result.ALREADY_DONE);
    }

    // ---- rows 2–5: refund outcomes ----

    @Test
    void row2ASecondPaymentForACompletedCheckoutRequestsAFullRefund() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        assertThat(payAndProcess(c, soon())).isEqualTo(Result.PROCESSED);
        String second = paid(c, soon().plusSeconds(2));

        assertThat(process(event(second))).isEqualTo(Result.PROCESSED);

        assertRefundOutcome(second, PaymentOutcome.DUPLICATE_PAYMENT);
        assertThat(periods(owner)).as("the first payment's period only").isEqualTo(1);
    }

    @Test
    void row3APaymentAfterTheQuoteExpiredRequestsARefund() {
        Instant expires = micros(Instant.now().plus(2, ChronoUnit.HOURS));
        String c = checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT, expires);
        String ref = paid(c, expires.plusSeconds(1));

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        assertRefundOutcome(ref, PaymentOutcome.LATE_PAYMENT);
        assertThat(current(owner)).isNull();
    }

    @Test
    void row3AtExactlyTheExpiryThePaymentIsHonoured() {
        Instant expires = micros(Instant.now().plus(2, ChronoUnit.HOURS));
        String c = checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT, expires);

        assertThat(payAndProcess(c, expires)).isEqualTo(Result.PROCESSED);

        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void row4APaymentAfterTheCancelRequestRequestsARefund() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        checkouts.cancel(owner.businessId(), c);
        Instant cancelledAt = jdbc.queryForObject("select cancel_requested_at from checkout_session where id = ?", Timestamp.class, c).toInstant();
        String ref = paid(c, cancelledAt.plusMillis(1));

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        assertRefundOutcome(ref, PaymentOutcome.PAID_AFTER_CANCEL);
        assertThat(current(owner)).isNull();
    }

    @Test
    void row4APaymentAtTheCancelInstantIsHonouredAndTheCheckoutKeepsItsCancelState() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        checkouts.cancel(owner.businessId(), c);
        Instant cancelledAt = jdbc.queryForObject("select cancel_requested_at from checkout_session where id = ?", Timestamp.class, c).toInstant();

        assertThat(payAndProcess(c, cancelledAt)).isEqualTo(Result.PROCESSED);

        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(checkoutStatus(c)).isEqualTo("CANCEL_REQUESTED");
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void row5APlanDisabledBeforeThePaymentRequestsARefund() {
        Instant tPay = soon();
        disableModel4(tPay.minusSeconds(10));
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, tPay);

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        assertRefundOutcome(ref, PaymentOutcome.PLAN_NOT_ELIGIBLE);
    }

    @Test
    void row5APlanDisabledAtExactlyThePaymentTimeIsNoLongerEligible() {
        Instant tPay = soon();
        disableModel4(tPay);
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, tPay);

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        assertRefundOutcome(ref, PaymentOutcome.PLAN_NOT_ELIGIBLE);
    }

    @Test
    void row5APaymentBeforeTheDisableIsEligible() {
        Instant tPay = soon();
        disableModel4(tPay.plusSeconds(10));
        String c = checkout(owner, CheckoutKind.NEW, null);

        assertThat(payAndProcess(c, tPay)).isEqualTo(Result.PROCESSED);

        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"row2", "row3", "row4", "row5"})
    void withoutAConfirmedIdempotentRefundEachRefundRowIsAManualExceptionKeepingItsCause(String row) {
        fakeEt.capabilities(ProviderCapabilities.none());
        String cause;
        String ref;
        switch (row) {
            case "row2" -> {
                String c = checkout(owner, CheckoutKind.NEW, null);
                payAndProcess(c, soon());
                ref = paid(c, soon().plusSeconds(2));
                cause = PaymentOutcome.DUPLICATE_PAYMENT;
            }
            case "row3" -> {
                Instant expires = micros(Instant.now().plus(1, ChronoUnit.HOURS));
                ref = paid(checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT, expires), expires.plusSeconds(1));
                cause = PaymentOutcome.LATE_PAYMENT;
            }
            case "row4" -> {
                String c = checkout(owner, CheckoutKind.NEW, null);
                checkouts.cancel(owner.businessId(), c);
                ref = paid(c, soon());
                cause = PaymentOutcome.PAID_AFTER_CANCEL;
            }
            default -> {
                Instant tPay = soon();
                disableModel4(tPay.minusSeconds(1));
                ref = paid(checkout(owner, CheckoutKind.NEW, null), tPay);
                cause = PaymentOutcome.PLAN_NOT_ELIGIBLE;
            }
        }

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        String paymentId = paymentId(ref);
        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where payment_id = ?", paymentId);
        assertThat(x.get("code")).isEqualTo(PaymentOutcome.REFUND_REQUIRED_MANUAL);
        assertThat(x.get("status")).isEqualTo("OPEN");
        assertThat(x.get("refund_request_id")).isNull();
        assertThat(x.get("facts").toString()).contains(cause);
        assertThat(count("select count(*) from refund_request where payment_id = ?", paymentId)).as("no refund request without the capability").isZero();
        assertEveryChargeAccountedForOnce();
    }

    // ---- row 6 ----

    @Test
    void row6AChangedCurrentSubscriptionIsAnExceptionWithNoChangeAndNoRefund() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String manual = MonetizationFixtures.subscription(jdbc, owner.businessId(), "MODEL_4", 1, admin.getId());
        String ref = paid(c, soon());

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where payment_id = ?", paymentId(ref));
        assertThat(x.get("code")).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(x.get("status")).isEqualTo("OPEN");
        assertThat(refunds(owner)).as("BD-17: no refund").isZero();
        assertThat(current(owner)).as("BD-17: no subscription change").isEqualTo(manual);
        assertThat(providerSubscriptions(owner)).isZero();
        assertThat(periods(owner)).isZero();
        assertThat(checkoutStatus(c)).isEqualTo("OPEN");
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void row6ARenewalOfASubscriptionThatIsNoLongerCurrentChangesNothing() {
        String sub = activate(owner, soon());
        String renewal = checkout(owner, CheckoutKind.RENEWAL, null); // expects no current row, but one exists now
        String ref = paid(renewal, soon().plusSeconds(3));

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, paymentId(ref))).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(periods(owner)).isEqualTo(1);
        assertThat(current(owner)).isEqualTo(sub);
        assertThat(refunds(owner)).isZero();
    }

    // ---- evidence refused by the lifecycle ----

    @Test
    void evidenceTheLifecycleRefusesBecomesAnExceptionInANewTransactionWithNoPartialWrites() {
        String c = checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT + 500, Instant.now().plus(23, ChronoUnit.HOURS));
        String ref = paid(c, soon());

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where payment_id = ?", paymentId(ref));
        assertThat(x.get("code")).isEqualTo(PaymentOutcomeService.EVIDENCE_INVALID);
        assertThat(x.get("status")).isEqualTo("OPEN");
        assertThat(x.get("facts").toString()).contains("AMOUNT_MISMATCH");
        assertThat(count("select count(*) from billing_invoice where business_id = ?", owner.businessId())).as("the refused attempt rolled back").isZero();
        assertThat(jdbc.queryForObject("select invoice_id from payment where provider_payment_ref = ?", String.class, ref)).isNull();
        assertThat(current(owner)).isNull();
        assertThat(checkoutStatus(c)).isEqualTo("OPEN");
        assertThat(refunds(owner)).isZero();
        assertEveryChargeAccountedForOnce();
    }

    // ---- ledger idempotency (R5) ----

    @Test
    void theSamePaymentThroughTwoEventsIsRecordedAndConsumedOnce() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        String first = event(ref), second = event(ref);

        assertThat(process(first)).isEqualTo(Result.PROCESSED);
        assertThat(process(second)).isEqualTo(Result.PROCESSED);

        assertThat(payments(owner)).isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
        assertThat(eventRow(second).get("status")).isEqualTo("PROCESSED");
        assertThat(fakeEt.verifyCalls()).as("each event is verified on its own").isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "paid-at"})
    void aReFetchThatContradictsTheLedgerIsALedgerFactsMismatchAndChangesNothing(String what) {
        String c = checkout(owner, CheckoutKind.NEW, null);
        Instant tPay = soon();
        String ref = paid(c, tPay);
        process(event(ref));
        Map<String, Object> before = jdbc.queryForMap("select * from payment where provider_payment_ref = ?", ref);
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(what.equals("amount") ? ET_AMOUNT + 1 : ET_AMOUNT, Currency.ETB),
                what.equals("paid-at") ? tPay.plusSeconds(1) : tPay, metadata(c)));

        String e = event(ref);
        assertThat(process(e)).isEqualTo(Result.PROCESSED);

        Map<String, Object> x = onlyException(owner);
        assertThat(x.get("code")).isEqualTo(PaymentOutcomeService.LEDGER_FACTS_MISMATCH);
        assertThat(x.get("status")).isEqualTo("OPEN");
        assertThat(x.get("payment_id")).isEqualTo(before.get("id"));
        assertThat(jdbc.queryForMap("select * from payment where provider_payment_ref = ?", ref)).as("the ledger is immutable").isEqualTo(before);
        assertThat(periods(owner)).isEqualTo(1);
        assertThat(refunds(owner)).isZero();
        assertThat(eventRow(e).get("status")).isEqualTo("PROCESSED");
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void aRenewalWithNothingToRenewIsRefusedAsAChangedStateWithNoPartialWrites() {
        String c = checkout(owner, CheckoutKind.RENEWAL, null);
        String ref = paid(c, soon());

        assertThat(process(event(ref))).isEqualTo(Result.PROCESSED);

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where payment_id = ?", paymentId(ref));
        assertThat(x.get("code")).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(x.get("facts").toString()).contains("ExpectedSubscriptionChangedException");
        assertThat(count("select count(*) from billing_invoice where business_id = ?", owner.businessId())).isZero();
        assertThat(current(owner)).isNull();
        assertThat(refunds(owner)).isZero();
        assertEveryChargeAccountedForOnce();
    }

    // ---- non-final and declined outcomes (D2, R7) ----

    @Test
    void aPendingOutcomeIsUnresolvedAndRetriedNeverAFailure() {
        fakeEt.script(FakeProviderClient.Script.DELAY).delayedFetches(1);
        String c = checkout(owner, CheckoutKind.NEW, null);
        String e = event(paid(c, soon()));

        assertThatThrownBy(() -> process(e)).isInstanceOf(TransientJobException.class);

        assertThat(eventRow(e)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((String) eventRow(e).get("last_error")).contains("UNRESOLVED");
        assertThat(count("select count(*) from payment")).isZero();
        assertThat(count("select count(*) from billing_exception")).as("not VERIFICATION_FAILED").isZero();
        assertThat(current(owner)).isNull();

        assertThat(process(e)).isEqualTo(Result.PROCESSED);
        assertThat(providerSubscriptions(owner)).isEqualTo(1);
    }

    @Test
    void aDefiniteDeclineRecordsNothingFinancialAndFailsTheEvent() {
        fakeEt.script(FakeProviderClient.Script.DECLINE);
        String c = checkout(owner, CheckoutKind.NEW, null);
        String e = event(paid(c, soon()));

        assertThat(process(e)).isEqualTo(Result.DECLINED);

        assertThat(eventRow(e).get("status")).isEqualTo("FAILED");
        assertThat((String) eventRow(e).get("last_error")).contains("PROVIDER_DECLINED");
        assertThat(count("select count(*) from payment")).isZero();
        assertThat(count("select count(*) from billing_exception")).isZero();
        assertThat(count("select count(*) from refund_request")).isZero();
        assertThat(current(owner)).isNull();
        assertThat(checkoutStatus(c)).as("left to expire").isEqualTo("OPEN");
    }

    @Test
    void aProviderTimeoutIsTransientAndLeavesTheEventPending() {
        fakeEt.script(FakeProviderClient.Script.VERIFY_TIMEOUT);
        String e = event(paid(checkout(owner, CheckoutKind.NEW, null), soon()));

        assertThatThrownBy(() -> process(e)).isInstanceOf(TransientJobException.class);

        assertThat(eventRow(e).get("status")).isEqualTo("PENDING");
        assertThat(count("select count(*) from payment")).isZero();
    }

    // ---- AC2, AC4 ----

    @Test
    void outOfOrderEventsForTwoPaymentsOfOneCheckoutEndTheSameWay() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String a = paid(c, soon()), b = paid(c, soon().plusSeconds(1));
        String eventA = event(a), eventB = event(b);

        assertThat(process(eventB)).isEqualTo(Result.PROCESSED);
        assertThat(process(eventA)).isEqualTo(Result.PROCESSED);

        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select p.provider_payment_ref from subscription_period sp join payment p on p.id = sp.payment_id where sp.business_id = ?", String.class, owner.businessId())).isEqualTo(b);
        assertRefundOutcome(a, PaymentOutcome.DUPLICATE_PAYMENT);
    }

    @Test
    void aStalePendingEventProcessedAfterTheSuccessChangesNothing() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        String success = event(ref);
        String stale = event(ref);

        assertThat(process(success)).isEqualTo(Result.PROCESSED);
        assertThat(process(stale)).isEqualTo(Result.PROCESSED);

        assertThat(payments(owner)).isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void anEventIsProcessedOnce() {
        String e = event(paid(checkout(owner, CheckoutKind.NEW, null), soon()));

        assertThat(process(e)).isEqualTo(Result.PROCESSED);
        assertThat(process(e)).isEqualTo(Result.ALREADY_DONE);

        assertThat(payments(owner)).isEqualTo(1);
        assertThat(fakeEt.verifyCalls()).as("a finished event isn't fetched again").isEqualTo(1);
    }

    // ---- transactions ----

    @Test
    void processingRefusesToRunInsideATransaction() {
        String e = event(paid(checkout(owner, CheckoutKind.NEW, null), soon()));

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> outcomes.process(e))).isInstanceOf(ProviderCallInTransactionException.class);

        assertThat(fakeEt.verifyCalls()).isZero();
        assertThat(eventRow(e).get("status")).isEqualTo("PENDING");
    }

    // ---- helpers ----

    private String paymentId(String ref) {
        return jdbc.queryForObject("select id from payment where provider = ? and provider_payment_ref = ? and kind = 'CHARGE'", String.class, PROVIDER, ref);
    }

    private void assertRefundOutcome(String ref, String cause) {
        String paymentId = paymentId(ref);
        Map<String, Object> pay = jdbc.queryForMap("select * from payment where id = ?", paymentId);
        assertThat(pay.get("status")).isEqualTo("SUCCEEDED");
        assertThat(pay.get("verification")).isEqualTo(VERIFIED);
        assertThat(pay.get("invoice_id")).as("a refunded charge buys nothing").isNull();
        Map<String, Object> refund = jdbc.queryForMap("select * from refund_request where payment_id = ?", paymentId);
        assertThat(refund.get("status")).as("requested, never submitted here").isEqualTo("REQUESTED");
        assertThat(refund.get("amount_minor")).as("a full refund").isEqualTo(pay.get("amount_minor"));
        assertThat(refund.get("reason")).isEqualTo(cause);
        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where payment_id = ?", paymentId);
        assertThat(x.get("code")).isEqualTo(cause);
        assertThat(x.get("status")).isEqualTo("AUTO_HANDLING");
        assertThat(x.get("refund_request_id")).isEqualTo(refund.get("id"));
        assertThat(count("select count(*) from subscription_period where payment_id = ?", paymentId)).isZero();
        assertThat(fakeEt.refundsCreated()).as("no refund is submitted before SUB-R4-13").isZero();
        assertEveryChargeAccountedForOnce();
    }

    /** A new configuration version, in force from {@code from}, that no longer enables MODEL_4. */
    private void disableModel4(Instant from) {
        int version = jdbc.queryForObject("select max(version) + 1 from monetization_config", Integer.class);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, false, false, ?, now(), ?)", version, Timestamp.from(from), admin.getId());
    }
}
