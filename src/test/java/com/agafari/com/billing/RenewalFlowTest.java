package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderInvoiceState;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderSubscriptionSnapshot;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.ProviderGrace;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R5-03 (addendum §12, S1–S16) on PostgreSQL, through the real inbox, outcome service, lifecycle, ledger and evidence
 * triggers. The provider is the scriptable fake: <b>SYNTHETIC</b> provider answers shaped like the verified Stripe re-fetches
 * (the authentic RF capture is replayed separately by {@code AuthenticRenewalContractTest}). Amounts are fixtures (AD-10 open).
 */
class RenewalFlowTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Duration GRACE = Duration.ofDays(ProviderGrace.DAYS);

    @Autowired private RenewalSweep sweep;
    @Autowired private EffectiveStateEvaluator evaluator;

    private String priceRef;

    @BeforeEach
    void providerPrice() {
        priceRef = "price_fake_" + SEQ.incrementAndGet();
        // prices are append-only: this test's MODEL_4 price is a new version that carries the provider price (R5-02 shape)
        price = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', ?, 90, ?, true, now() - interval '400 days')", price, ET_AMOUNT, priceRef);
        evaluator.setPeriodEndEnabled(false);
    }

    @AfterEach
    void restore() {
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    // ---- fixtures ----

    private record Sub(Tenant tenant, String id, String ref, String customer, Instant periodStart, Instant periodEnd) {
        Instant graceEnd() {
            return periodEnd.plus(GRACE);
        }
    }

    /** A PROVIDER row created through the real checkout path, with a provider subscription and customer (R5-02 shape). */
    private Sub provider(Tenant t) {
        String n = SEQ.incrementAndGet() + "_" + t.businessId().substring(0, 6);
        String subRef = "sub_fake_" + n, cusRef = "cus_fake_" + n, in = "in_first_" + n;
        Instant start = micros(clock.now().minus(1, ChronoUnit.DAYS)), end = oneMonth(start), tPay = micros(clock.now());
        String c = checkout(t, CheckoutKind.NEW, null);
        ProviderBilling first = new ProviderBilling(in, subRef, cusRef, priceRef, PriceInterval.MONTH, start, end, "pi_first_" + n, null);
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), tPay, metadata(c), first));
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        Map<String, Object> row = jdbc.queryForMap("select id, period_end from business_subscription where business_id = ? and status <> 'EXPIRED'", t.businessId());
        assertThat(((Timestamp) row.get("period_end")).toInstant()).isEqualTo(end);
        return new Sub(t, (String) row.get("id"), subRef, cusRef, start, end);
    }

    private String renewal(Sub s, Instant start, Instant end, Instant tPay) {
        return renewal(s, s.ref(), s.customer(), priceRef, start, end, tPay, new Money(ET_AMOUNT, Currency.ETB));
    }

    private String renewal(Sub s, String subRef, String cusRef, String priceRefCharged, Instant start, Instant end, Instant tPay, Money amount) {
        String in = "in_renew_" + SEQ.incrementAndGet();
        ProviderBilling b = ProviderBilling.renewal(in, subRef, cusRef, priceRefCharged, PriceInterval.MONTH, start, end, "pi_renew_" + in);
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, amount, tPay, Map.of(), b));
        return in;
    }

    private String nextRenewal(Sub s, Instant tPay) {
        return renewal(s, s.periodEnd(), oneMonth(s.periodEnd()), tPay);
    }

    /** A signed state event (the fake names the object to re-fetch in {@code state_kind}/{@code state_ref}). */
    private String stateEvent(String type, String kind, String ref) {
        String eventId = "evt_state_" + SEQ.incrementAndGet();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"state_kind\":\"" + kind + "\",\"state_ref\":\"" + ref + "\"}").getBytes(StandardCharsets.UTF_8);
        inbox.store(PROVIDER, new InboundEvent(eventId, type), body);
        return jdbc.queryForObject("select id from provider_event where provider = ? and event_id = ?", String.class, PROVIDER, eventId);
    }

    private String failedInvoice(Sub s) {
        String in = "in_failed_" + SEQ.incrementAndGet();
        fakeEt.invoiceState(new ProviderInvoiceState(in, ProviderInvoiceState.Status.OPEN, "subscription_cycle", s.ref(), s.customer(), s.periodEnd(), oneMonth(s.periodEnd()), true, false));
        return in;
    }

    private void at(Instant t) {
        clock.setOffset(Duration.between(Instant.now(), t));
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from business_subscription where id = ?", id);
    }

    private String status(String id) {
        return (String) row(id).get("status");
    }

    private Instant periodEnd(String id) {
        return ((Timestamp) row(id).get("period_end")).toInstant();
    }

    private String eventStatus(String eventRowId) {
        return (String) eventRow(eventRowId).get("status");
    }

    private List<String> codes(Tenant t) {
        return jdbc.queryForList("select code from billing_exception where business_id = ? order by created_at", String.class, t.businessId());
    }

    // ---- S16 / AD-7: grace recorded at activation ----

    @Test
    void s16ANewProviderActivationRecordsSevenDaysOfGrace() {
        Sub s = provider(owner);
        assertThat(row(s.id()).get("grace_days")).as("AD-7: 7 calendar days, PROVIDER only").isEqualTo(7);
        assertThat(row(s.id())).containsEntry("provider_subscription_ref", s.ref());
        assertThat(jdbc.queryForObject("select provider_customer_ref from billing_account where business_id = ?", String.class, owner.businessId())).isEqualTo(s.customer());
    }

    // ---- S1: renewal before period_end ----

    @Test
    void s1ARenewalPaidBeforePeriodEndExtendsTheSameRowContiguouslyAndOnlyOnce() {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().minus(1, ChronoUnit.HOURS));
        at(tPay.plusSeconds(60));
        String in = nextRenewal(s, tPay);
        String evt = event(in);
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        assertThat(current(owner)).isEqualTo(s.id());
        assertThat(status(s.id())).isEqualTo("ACTIVE");
        assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
        Map<String, Object> inv = jdbc.queryForMap("select * from billing_invoice where provider_invoice_ref = ?", in);
        assertThat(inv.get("checkout_session_id")).as("a renewal has no Agafari checkout (§37: never reused)").isNull();
        assertThat(((Timestamp) inv.get("period_start")).toInstant()).isEqualTo(s.periodEnd());
        Map<String, Object> pay = jdbc.queryForMap("select * from payment where provider_payment_ref = ?", in);
        assertThat(pay).containsEntry("checkout_session_id", null).containsEntry("verification", "SIGNED_WEBHOOK+API_FETCH").containsEntry("business_id", owner.businessId());
        assertThat(periods(owner)).isEqualTo(2);
        assertThat(exceptions(owner)).isZero();

        // S9: the same event again, and a second event for the same invoice: applied once
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.ALREADY_DONE);
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periods(owner)).isEqualTo(2);
        assertThat(payments(owner)).isEqualTo(2);
        assertEveryChargeAccountedForOnce();
    }

    // ---- S2: first attempt fails ----

    @Test
    void s2APaymentFailureMakesTheRowPastDueFromPeriodEndAndRepeatsChangeNothing() {
        Sub s = provider(owner);
        at(s.periodEnd().plus(2, ChronoUnit.HOURS));
        String in = failedInvoice(s);
        String evt = stateEvent("invoice.payment_failed", "INVOICE", in);
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).isEqualTo("PAST_DUE");
        assertThat(((Timestamp) row(s.id()).get("past_due_since")).toInstant()).isEqualTo(s.periodEnd());
        assertThat(eventStatus(evt)).isEqualTo("PROCESSED");

        String again = stateEvent("invoice.payment_failed", "INVOICE", in);
        assertThat(process(again)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(eventStatus(again)).as("a retry that fails again changes nothing").isEqualTo("IGNORED");
        assertThat(status(s.id())).isEqualTo("PAST_DUE");
        assertThat(payments(owner)).isEqualTo(1);
    }

    @Test
    void s2AFailureEventBeforeTheAgafariPeriodEndChangesNothing() {
        Sub s = provider(owner);
        String evt = stateEvent("invoice.payment_failed", "INVOICE", failedInvoice(s));
        process(evt);
        assertThat(eventStatus(evt)).isEqualTo("IGNORED");
        assertThat(status(s.id())).isEqualTo("ACTIVE");
    }

    @Test
    void aSubscriptionUpdatedToPastDueActsThroughItsLatestInvoice() {
        Sub s = provider(owner);
        at(s.periodEnd().plus(2, ChronoUnit.HOURS));
        String in = failedInvoice(s);
        fakeEt.subscriptionState(new ProviderSubscriptionSnapshot(s.ref(), s.customer(), ProviderSubscriptionState.Status.PAST_DUE, null, in, false));
        assertThat(process(stateEvent("customer.subscription.updated", "SUBSCRIPTION", s.ref()))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).isEqualTo("PAST_DUE");
    }

    // ---- S3: recovery during grace ----

    @Test
    void s3RecoveryOnDayThreeOfGraceReactivatesWithThePeriodStartingAtTheOldPeriodEnd() {
        Sub s = provider(owner);
        at(s.periodEnd().plus(2, ChronoUnit.HOURS));
        process(stateEvent("invoice.payment_failed", "INVOICE", failedInvoice(s)));
        assertThat(status(s.id())).isEqualTo("PAST_DUE");

        Instant tPay = micros(s.periodEnd().plus(3, ChronoUnit.DAYS));
        at(tPay.plusSeconds(30));
        assertThat(process(event(nextRenewal(s, tPay)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).isEqualTo("ACTIVE");
        assertThat(row(s.id()).get("past_due_since")).isNull();
        assertThat(periodEnd(s.id())).as("grace time is part of the paid period").isEqualTo(oneMonth(s.periodEnd()));
        assertThat(periods(owner)).isEqualTo(2);
        assertEveryChargeAccountedForOnce();
    }

    // ---- S4: no payment ----

    @Test
    void s4WithoutPaymentTheRowEndsSevenDaysAfterPeriodEnd() {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        at(s.periodEnd().plus(6, ChronoUnit.DAYS));
        materialize(owner);
        assertThat(status(s.id())).isEqualTo("PAST_DUE");
        at(s.graceEnd().plusSeconds(1));
        materialize(owner);
        Map<String, Object> r = row(s.id());
        assertThat(r).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED");
        assertThat(((Timestamp) r.get("ended_at")).toInstant()).isEqualTo(s.graceEnd());
    }

    // ---- S5: the payment time decides at the grace boundary ----

    @Test
    void s5APaymentExactlyAtTheGraceEndRecoversEvenWhenProcessedLate() {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        Instant tPay = s.graceEnd();
        at(tPay.plus(2, ChronoUnit.HOURS)); // the row isn't materialized yet: the job is late
        assertThat(process(event(nextRenewal(s, tPay)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).isEqualTo("ACTIVE");
        assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void s5APaymentOneSecondAfterTheGraceEndIsPaidAfterExpiry() {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        Instant tPay = s.graceEnd().plusSeconds(1);
        at(tPay.plus(2, ChronoUnit.HOURS));
        assertThat(process(event(nextRenewal(s, tPay)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).as("never reactivated").isEqualTo("EXPIRED");
        assertThat(current(owner)).isNull();
        assertThat(codes(owner)).containsExactly(RenewalOutcome.PAID_AFTER_EXPIRY);
        assertThat(refunds(owner)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    // ---- S6: late payment after EXPIRED ----

    @Test
    void s6APaymentAfterTheRowEndedIsRecordedRefundedAndNeverReactivates() {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        at(s.graceEnd().plus(1, ChronoUnit.DAYS));
        materialize(owner);
        assertThat(status(s.id())).isEqualTo("EXPIRED");
        Instant tPay = micros(clock.now().minus(1, ChronoUnit.HOURS));
        assertThat(process(event(nextRenewal(s, tPay)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).isEqualTo("EXPIRED");
        assertThat(providerSubscriptions(owner)).as("never a new row from a renewal invoice").isEqualTo(1);
        Map<String, Object> e = onlyException(owner);
        assertThat(e).containsEntry("code", RenewalOutcome.PAID_AFTER_EXPIRY).containsEntry("status", "AUTO_HANDLING");
        assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, e.get("refund_request_id"))).isEqualTo("REQUESTED");
        assertThat(count("select count(*) from admin_audit_event where action = 'PROVIDER_SUBSCRIPTION_REVIEW_REQUIRED' and target_id = ?", s.ref())).isEqualTo(1);
    }

    // ---- S7: deletion ----

    @Test
    void s7ADeletionWhilePastDueEndsAtTheEarlierOfDeletionAndGraceEnd() {
        Sub s = provider(owner);
        at(s.periodEnd().plus(2, ChronoUnit.HOURS));
        String in = failedInvoice(s);
        process(stateEvent("invoice.payment_failed", "INVOICE", in));
        Instant deleted = micros(s.periodEnd().plus(5, ChronoUnit.DAYS));
        at(deleted.plus(1, ChronoUnit.HOURS));
        fakeEt.subscriptionState(new ProviderSubscriptionSnapshot(s.ref(), s.customer(), ProviderSubscriptionState.Status.CANCELED, deleted, in, false));
        String evt = stateEvent("customer.subscription.deleted", "SUBSCRIPTION", s.ref());
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        Map<String, Object> r = row(s.id());
        assertThat(r).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED");
        assertThat(((Timestamp) r.get("ended_at")).toInstant()).as("D-R3: the earlier of deletion and grace end").isEqualTo(deleted);

        String late = stateEvent("customer.subscription.deleted", "SUBSCRIPTION", s.ref());
        process(late);
        assertThat(eventStatus(late)).as("already ended: audit only").isEqualTo("IGNORED");
    }

    @Test
    void s7RetriesExhaustedWithoutAFailureEventStillEndsThroughPastDue() {
        Sub s = provider(owner);
        Instant deleted = micros(s.periodEnd().plus(7, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS));
        at(deleted.plus(10, ChronoUnit.MINUTES));
        fakeEt.subscriptionState(new ProviderSubscriptionSnapshot(s.ref(), s.customer(), ProviderSubscriptionState.Status.CANCELED, deleted, failedInvoice(s), false));
        process(stateEvent("customer.subscription.deleted", "SUBSCRIPTION", s.ref()));
        assertThat(row(s.id())).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED");
    }

    @Test
    void aDeletionWithAPaidLatestInvoiceAppliesThatPaymentFirst() {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().plus(1, ChronoUnit.DAYS));
        String in = nextRenewal(s, tPay);
        fakeEt.invoiceState(new ProviderInvoiceState(in, ProviderInvoiceState.Status.PAID, "subscription_cycle", s.ref(), s.customer(), s.periodEnd(), oneMonth(s.periodEnd()), true, false));
        at(tPay.plus(1, ChronoUnit.DAYS));
        fakeEt.subscriptionState(new ProviderSubscriptionSnapshot(s.ref(), s.customer(), ProviderSubscriptionState.Status.CANCELED, tPay.plus(2, ChronoUnit.HOURS), in, false));
        String evt = stateEvent("customer.subscription.deleted", "SUBSCRIPTION", s.ref());
        assertThatThrownBy(() -> process(evt)).isInstanceOf(TransientJobException.class);
        String internal = jdbc.queryForObject("select id from provider_event where event_id = ?", String.class, "agafari-reconciliation:" + in);
        assertThat(process(internal)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(status(s.id())).as("cancelled with the period paid: recorded only (R5-04 owns voluntary cancels)").isEqualTo("ACTIVE");
    }

    // ---- S8: out of order ----

    @Test
    void s8AFailureEventAfterThePaymentIsStaleAndIgnored() {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().plus(1, ChronoUnit.HOURS));
        at(tPay.plusSeconds(60));
        String in = nextRenewal(s, tPay);
        process(event(in));
        fakeEt.invoiceState(new ProviderInvoiceState(in, ProviderInvoiceState.Status.PAID, "subscription_cycle", s.ref(), s.customer(), s.periodEnd(), oneMonth(s.periodEnd()), true, false));
        String evt = stateEvent("invoice.payment_failed", "INVOICE", in);
        process(evt);
        assertThat(eventStatus(evt)).isEqualTo("IGNORED");
        assertThat(status(s.id())).isEqualTo("ACTIVE");
    }

    @Test
    void s8AFailureEventSeenAfterTheInvoiceWasPaidButBeforeThePaymentIsAppliedNeverMarksPastDue() {
        // the re-fetch, not the event, decides: the invoice is already paid, its invoice.paid simply hasn't been processed yet
        Sub s = provider(owner);
        at(s.periodEnd().plus(3, ChronoUnit.HOURS));
        String in = nextRenewal(s, micros(s.periodEnd().plus(2, ChronoUnit.HOURS)));
        fakeEt.invoiceState(new ProviderInvoiceState(in, ProviderInvoiceState.Status.PAID, "subscription_cycle", s.ref(), s.customer(), s.periodEnd(), oneMonth(s.periodEnd()), true, false));
        String failed = stateEvent("invoice.payment_failed", "INVOICE", in);
        process(failed);
        assertThat(eventStatus(failed)).isEqualTo("IGNORED");
        assertThat(status(s.id())).as("never PAST_DUE for a paid invoice").isEqualTo("ACTIVE");
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
    }

    @Test
    void s8ALaterPeriodWaitsForTheMissingOneThenBecomesAnExceptionAfterGrace() {
        Sub s = provider(owner);
        Instant n1 = oneMonth(s.periodEnd());
        Instant tPay = micros(s.periodEnd().plus(1, ChronoUnit.DAYS));
        at(tPay.plusSeconds(60));
        String in = renewal(s, n1, oneMonth(n1), tPay);
        String evt = event(in);
        assertThatThrownBy(() -> process(evt)).isInstanceOf(TransientJobException.class);
        assertThat(eventRow(evt)).containsEntry("status", "PENDING");
        assertThat((String) eventRow(evt).get("last_error")).contains(RenewalOutcome.PENDING_ORDER);
        assertThat(payments(owner)).isEqualTo(1);

        at(s.graceEnd().plusSeconds(1));
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(codes(owner)).containsExactly(RenewalOutcome.PERIOD_MISMATCH);
        assertEveryChargeAccountedForOnce();
    }

    // ---- S10: lost webhook, renewal sweep (P-R1) ----

    @Test
    void s10TheSweepFindsAPaidRenewalWhoseWebhookWasLost() {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().plus(10, ChronoUnit.MINUTES));
        String in = nextRenewal(s, tPay);
        fakeEt.invoiceState(new ProviderInvoiceState(in, ProviderInvoiceState.Status.PAID, "subscription_cycle", s.ref(), s.customer(), s.periodEnd(), oneMonth(s.periodEnd()), true, false));
        fakeEt.subscriptionState(new ProviderSubscriptionSnapshot(s.ref(), s.customer(), ProviderSubscriptionState.Status.ACTIVE, null, in, false));

        at(s.periodEnd().plus(30, ChronoUnit.MINUTES));
        assertThat(planSweep()).as("not before period_end + 1 h").isZero();
        at(s.periodEnd().plus(61, ChronoUnit.MINUTES));
        assertThat(planSweep()).isEqualTo(1);
        assertThat(planSweep()).as("one check per slot").isEqualTo(1);

        sweep.check(new RenewalSweep.CheckPayload(s.id(), s.periodEnd().toString()));
        String internal = jdbc.queryForObject("select id from provider_event where event_id = ?", String.class, "agafari-reconciliation:" + in);
        assertThat(process(internal)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
        assertThat(jdbc.queryForObject("select verification from payment where provider_payment_ref = ?", String.class, in)).isEqualTo("RECONCILIATION_API_FETCH");
        assertThat(planSweep()).as("renewed: no further check is planned").isEqualTo(1);
    }

    @Test
    void s10TheSweepSlotsArePeriodEndPlusOneHourThenDailyThenSixHoursBeforeGraceEnd() {
        Instant pe = Instant.parse("2031-03-01T00:00:00Z"), ge = pe.plus(GRACE);
        assertThat(RenewalSweep.slot(pe, ge, pe.plus(59, ChronoUnit.MINUTES))).isNull();
        assertThat(RenewalSweep.slot(pe, ge, pe.plus(1, ChronoUnit.HOURS))).isEqualTo("d0");
        assertThat(RenewalSweep.slot(pe, ge, pe.plus(25, ChronoUnit.HOURS))).isEqualTo("d1");
        assertThat(RenewalSweep.slot(pe, ge, ge.minus(6, ChronoUnit.HOURS))).isEqualTo("final");
        assertThat(RenewalSweep.slot(pe, ge, ge)).isNull();
    }

    // ---- S11: identity and evidence checks ----

    @Test
    void s11AnUnknownSubscriptionIsQuarantinedWithNoOwnerInferred() {
        Sub s = provider(owner);
        at(s.periodEnd().plusSeconds(60));
        String in = renewal(s, "sub_unknown_" + SEQ.incrementAndGet(), s.customer(), priceRef, s.periodEnd(), oneMonth(s.periodEnd()), micros(s.periodEnd()),
                new Money(ET_AMOUNT, Currency.ETB));
        String evt = event(in);
        assertThat(process(evt)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat((String) eventRow(evt).get("last_error")).contains(RenewalOutcome.UNKNOWN_SUBSCRIPTION);
        assertThat(payments(owner)).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void s11ACustomerMismatchIsHeldForReviewWithoutExtension() {
        Sub s = provider(owner);
        at(s.periodEnd().plusSeconds(60));
        String in = renewal(s, s.ref(), "cus_other", priceRef, s.periodEnd(), oneMonth(s.periodEnd()), micros(s.periodEnd()), new Money(ET_AMOUNT, Currency.ETB));
        process(event(in));
        assertThat(periodEnd(s.id())).isEqualTo(s.periodEnd());
        assertThat(codes(owner)).containsExactly(RenewalOutcome.CUSTOMER_MISMATCH);
        assertThat(refunds(owner)).as("P-R4: held for manual review").isZero();
        assertThat(jdbc.queryForObject("select provider_customer_ref from billing_account where business_id = ?", String.class, owner.businessId()))
                .as("U5: never re-mapped").isEqualTo(s.customer());
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void s11APriceOrAmountMismatchIsHeldForReview() {
        Sub s = provider(owner);
        Sub t = provider(data.tenant("renewal-amount")); // activated now, before the clock moves (its checkout expires in real time)
        at(s.periodEnd().plusSeconds(60));
        process(event(renewal(s, s.ref(), s.customer(), "price_other", s.periodEnd(), oneMonth(s.periodEnd()), micros(s.periodEnd()), new Money(ET_AMOUNT, Currency.ETB))));
        process(event(renewal(t, t.ref(), t.customer(), priceRef, t.periodEnd(), oneMonth(t.periodEnd()), micros(t.periodEnd()), new Money(ET_AMOUNT + 1, Currency.ETB))));
        assertThat(codes(owner)).containsExactly(RenewalOutcome.AMOUNT_MISMATCH);
        assertThat(codes(t.tenant())).containsExactly(RenewalOutcome.AMOUNT_MISMATCH);
        assertThat(periodEnd(s.id())).isEqualTo(s.periodEnd());
        assertThat(periodEnd(t.id())).isEqualTo(t.periodEnd());
    }

    @Test
    void s11NonQualifyingEvidenceIsHeldForReview() {
        Sub s = provider(owner);
        at(s.periodEnd().plusSeconds(60));
        String in = "in_nq_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(s.periodEnd()), Map.of(),
                ProviderBilling.renewalNotQualifying(in, s.ref(), s.customer(), "the invoice carries tax (tax is off, AD-16)")));
        process(event(in));
        assertThat(codes(owner)).containsExactly(RenewalOutcome.EVIDENCE_NOT_QUALIFYING);
        assertThat(periodEnd(s.id())).isEqualTo(s.periodEnd());
    }

    // ---- S12: two invoices for one period ----

    @Test
    void s12ASecondInvoiceForAnAlreadyPaidPeriodIsRefunded() {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().minus(1, ChronoUnit.HOURS));
        at(tPay.plusSeconds(60));
        process(event(nextRenewal(s, tPay)));
        process(event(nextRenewal(s, tPay.plusSeconds(5))));
        assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
        assertThat(codes(owner)).containsExactly(RenewalOutcome.DUPLICATE_PERIOD);
        assertThat(refunds(owner)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    // ---- S13: tenant isolation ----

    @Test
    void s13TheSubscriptionRefAloneDecidesTheRestaurantAndMetadataIsIgnored() {
        Sub a = provider(owner);
        Sub b = provider(data.tenant("renewal-b"));
        at(b.periodEnd().minusSeconds(60));
        String in = "in_iso_" + SEQ.incrementAndGet();
        ProviderBilling bill = ProviderBilling.renewal(in, b.ref(), b.customer(), priceRef, PriceInterval.MONTH, b.periodEnd(), oneMonth(b.periodEnd()), "pi_iso");
        Map<String, String> pointingAtA = Map.of(PaymentMetadata.BUSINESS_ID, owner.businessId(), PaymentMetadata.CHECKOUT_ID, "any");
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(b.periodEnd().minusSeconds(120)), pointingAtA, bill));
        process(event(in));
        assertThat(periodEnd(b.id())).isEqualTo(oneMonth(b.periodEnd()));
        assertThat(periodEnd(a.id())).isEqualTo(a.periodEnd());
        assertThat(payments(owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select business_id from payment where provider_payment_ref = ?", String.class, in)).isEqualTo(b.tenant().businessId());
    }

    // ---- S14: concurrency ----

    @Test
    void s14AWebhookAndASweepForTheSameInvoiceProduceOneOutcome() throws Exception {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().plus(1, ChronoUnit.HOURS));
        at(tPay.plus(2, ChronoUnit.HOURS));
        String in = nextRenewal(s, tPay);
        String webhook = event(in);
        String internal = inbox.storeInternal(PROVIDER, in);
        List<Integer> done = race(2, i -> () -> {
            process(i == 0 ? webhook : internal);
            return 1;
        });
        assertThat(done).containsExactly(1, 1);
        assertThat(periods(owner)).isEqualTo(2);
        assertThat(payments(owner)).isEqualTo(2);
        assertThat(exceptions(owner)).isZero();
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void s14ARenewalAndTheGraceEndExpiryCannotBothWin() throws Exception {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        Instant tPay = s.graceEnd().minusSeconds(1);
        at(s.graceEnd().plusSeconds(30));
        String evt = event(nextRenewal(s, tPay));
        List<Integer> done = race(2, i -> () -> {
            if (i == 0) process(evt);
            else materialize(owner);
            return 1;
        });
        assertThat(done).containsExactly(1, 1);
        String st = status(s.id());
        if (st.equals("ACTIVE")) {
            assertThat(periodEnd(s.id())).isEqualTo(oneMonth(s.periodEnd()));
            assertThat(exceptions(owner)).isZero();
        } else {
            assertThat(st).isEqualTo("EXPIRED");
            assertThat(codes(owner)).containsExactly(RenewalOutcome.ROW_ENDED);
        }
        assertEveryChargeAccountedForOnce();
    }

    // ---- S15: trials and manual rows ----

    @Test
    void s15ManualAndTrialRowsGetNoGraceAndRenewalInvoicesNeverTouchThem() {
        startTrial(owner, clock.now().plus(10, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("select grace_days from business_subscription where business_id = ?", Integer.class, owner.businessId())).isNull();
        String in = "in_trial_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(clock.now()), Map.of(),
                ProviderBilling.renewal(in, "sub_none", "cus_none", priceRef, PriceInterval.MONTH, clock.now(), oneMonth(clock.now()), "pi_t")));
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat(jdbc.queryForObject("select status from business_subscription where business_id = ?", String.class, owner.businessId())).isEqualTo("TRIAL");
    }

    @Test
    void s16TheLifecycleRefusesAnyGraceOtherThanSeven() {
        assertThatThrownBy(() -> ProviderGrace.require(3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProviderGrace.require(null)).isEqualTo(7);
        assertThat(ProviderGrace.require(7)).isEqualTo(7);
    }

    // ---- helpers ----

    private int planSweep() {
        sweep.plan(clock.now());
        return count("select count(*) from job where type = ? and payload->>'subscriptionId' in (select id from business_subscription where business_id = ?)",
                RenewalSweep.CHECK_TYPE, owner.businessId());
    }

    private void materialize(Tenant t) {
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.materialize(t.businessId());
        });
    }
}
