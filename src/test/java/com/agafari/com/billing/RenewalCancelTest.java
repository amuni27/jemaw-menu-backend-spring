package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R5-03-C (addendum §7; decision N6, register §69): a payment made after the plan ended (R8) asks the existing
 * provider-subscription cancel job to cancel the still-live provider subscription, proven to be the ended row's own by the
 * renewal ownership proof (C2/C4 for payments without a checkout). Every failed check hands the payment's exception back to an
 * operator; nothing is ever cancelled for an active row. PostgreSQL; the fake ET provider (SYNTHETIC answers).
 */
class RenewalCancelTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ProviderCapabilities WITH_CANCEL = new ProviderCapabilities(true, true, true, Duration.ofHours(24), true, true);

    @Autowired private ProviderSubscriptionCancelHandler cancelJob;
    @Autowired private EffectiveStateEvaluator evaluator;

    private String priceRef;

    @BeforeEach
    void setUpCancel() {
        evaluator.setPeriodEndEnabled(true);
        fakeEt.capabilities(WITH_CANCEL);
        priceRef = "price_cancel_" + SEQ.incrementAndGet();
        price = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', ?, 92, ?, true, now() - interval '400 days')", price, ET_AMOUNT, priceRef);
    }

    @AfterEach
    void tearDownCancel() {
        evaluator.setPeriodEndEnabled(false);
        fakeEt.capabilities(com.agafari.com.billing.fake.FakeProviderClient.ALL);
        clock.reset();
    }

    private record Sub(Tenant tenant, String id, String ref, String customer, Instant periodEnd) {
    }

    private Sub provider(Tenant t) {
        String n = SEQ.incrementAndGet() + "_" + t.businessId().substring(0, 6);
        Instant start = micros(clock.now().minus(Duration.ofDays(1))), end = oneMonth(start);
        String c = checkout(t, CheckoutKind.NEW, null), in = "in_c_first_" + n;
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(clock.now()), metadata(c),
                new ProviderBilling(in, "sub_c_" + n, "cus_c_" + n, priceRef, PriceInterval.MONTH, start, end, "pi_c_" + n, null)));
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return new Sub(t, current(t), "sub_c_" + n, "cus_c_" + n, end);
    }

    /** The row ends for non-payment at its grace end; then a renewal invoice is paid after that. Returns the late payment's id. */
    private String paidAfterExpiry(Sub s, ProviderSubscriptionState providerSide) {
        clock.setOffset(Duration.between(Instant.now(), s.periodEnd().plus(Duration.ofDays(8))));
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(s.tenant().businessId());
            lifecycle.materialize(s.tenant().businessId());
        });
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, s.id())).isEqualTo("EXPIRED");
        String in = "in_c_late_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(clock.now().minusSeconds(60)), Map.of(),
                ProviderBilling.renewal(in, s.ref(), s.customer(), priceRef, PriceInterval.MONTH, s.periodEnd(), oneMonth(s.periodEnd()), "pi_" + in)));
        if (providerSide != null) fakeEt.subscriptionOfPayment(in, providerSide);
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, in);
    }

    private ProviderSubscriptionState live(Sub s) {
        return new ProviderSubscriptionState(s.ref(), s.customer(), ProviderSubscriptionState.Status.PAST_DUE, null, null, false, false);
    }

    private int cancelJobs(String paymentId) {
        return count("select count(*) from job where type = ? and key = ?", ProviderSubscriptionCancelHandler.TYPE, "payment:" + paymentId);
    }

    private String exceptionStatus(String paymentId) {
        return jdbc.queryForObject("select status from billing_exception where payment_id = ?", String.class, paymentId);
    }

    private int audits(String action, String target) {
        return count("select count(*) from admin_audit_event where action = ? and target_id = ?", action, target);
    }

    @Test
    void aLiveSubscriptionOfAnEndedRowIsCancelledOnceThroughTheExistingJob() {
        Sub s = provider(owner);
        String paymentId = paidAfterExpiry(s, live(s));
        assertThat(cancelJobs(paymentId)).isEqualTo(1);
        assertThat(audits(RenewalOutcomes.REVIEW_REQUIRED, s.ref())).as("handed to the job, not to an operator").isZero();

        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId));
        assertThat(fakeEt.cancelled()).containsExactly(s.ref());
        assertThat(audits(ProviderSubscriptionCancelHandler.CANCELLED, s.ref())).isEqualTo(1);
        assertThat(exceptionStatus(paymentId)).as("the refund keeps going").isEqualTo("AUTO_HANDLING");

        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId)); // a retry re-fetches first
        assertThat(fakeEt.cancelled()).as("never cancelled twice").hasSize(1);
        assertThat(audits(ProviderSubscriptionCancelHandler.ALREADY_CANCELLED, s.ref())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, s.id())).as("never reactivated").isEqualTo("EXPIRED");
    }

    @Test
    void anotherSubscriptionOnTheReFetchIsNeverCancelled() {
        Sub s = provider(owner);
        String paymentId = paidAfterExpiry(s, new ProviderSubscriptionState("sub_someone_else", s.customer(), ProviderSubscriptionState.Status.ACTIVE, null, null, false, false));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId));
        assertThat(fakeEt.cancelled()).isEmpty();
        assertThat(exceptionStatus(paymentId)).as("handed back to an operator").isEqualTo("OPEN");
    }

    @Test
    void anotherCustomerOnTheReFetchIsNeverCancelled() {
        Sub s = provider(owner);
        String paymentId = paidAfterExpiry(s, new ProviderSubscriptionState(s.ref(), "cus_someone_else", ProviderSubscriptionState.Status.ACTIVE, null, null, false, false));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId));
        assertThat(fakeEt.cancelled()).isEmpty();
        assertThat(exceptionStatus(paymentId)).isEqualTo("OPEN");
    }

    @Test
    void anotherRestaurantsEndedRowNeverProvesOwnership() {
        Sub a = provider(owner);
        Sub b = provider(data.tenant("renewal-cancel-b"));
        // B's late payment re-fetches A's subscription and customer: A's row isn't B's, so nothing is cancelled
        String paymentId = paidAfterExpiry(b, new ProviderSubscriptionState(a.ref(), b.customer(), ProviderSubscriptionState.Status.ACTIVE, null, null, false, false));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId));
        assertThat(fakeEt.cancelled()).isEmpty();
        assertThat(exceptionStatus(paymentId)).isEqualTo("OPEN");
    }

    @Test
    void theUnchangedChecksStillApplyLiveModeOtherOpenInvoicesAndStatus() {
        Sub s1 = provider(owner), s2 = provider(data.tenant("renewal-cancel-open")), s3 = provider(data.tenant("renewal-cancel-other")); // before the clock moves
        String live = paidAfterExpiry(s1, new ProviderSubscriptionState(s1.ref(), s1.customer(), ProviderSubscriptionState.Status.ACTIVE, null, null, false, true));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(live));
        String open = paidAfterExpiry(s2, new ProviderSubscriptionState(s2.ref(), s2.customer(), ProviderSubscriptionState.Status.ACTIVE, null, null, true, false));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(open));
        String other = paidAfterExpiry(s3, new ProviderSubscriptionState(s3.ref(), s3.customer(), ProviderSubscriptionState.Status.OTHER, null, null, false, false));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(other));
        assertThat(fakeEt.cancelled()).isEmpty();
        assertThat(exceptionStatus(live)).as("C3").isEqualTo("OPEN");
        assertThat(exceptionStatus(open)).as("C5").isEqualTo("OPEN");
        assertThat(exceptionStatus(other)).as("C6").isEqualTo("OPEN");
    }

    @Test
    void anAlreadyEndedSubscriptionIsOnlyRecorded() {
        Sub s = provider(owner);
        String paymentId = paidAfterExpiry(s, new ProviderSubscriptionState(s.ref(), s.customer(), ProviderSubscriptionState.Status.CANCELED, null, null, false, false));
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId));
        assertThat(fakeEt.cancelled()).isEmpty();
        assertThat(audits(ProviderSubscriptionCancelHandler.ALREADY_CANCELLED, s.ref())).isEqualTo(1);
        assertThat(exceptionStatus(paymentId)).isEqualTo("AUTO_HANDLING");
    }

    @Test
    void withoutTheCancelCapabilityAnOperatorReviewsItAsBefore() {
        fakeEt.capabilities(com.agafari.com.billing.fake.FakeProviderClient.ALL);
        Sub s = provider(owner);
        String paymentId = paidAfterExpiry(s, live(s));
        assertThat(cancelJobs(paymentId)).isZero();
        assertThat(audits(RenewalOutcomes.REVIEW_REQUIRED, s.ref())).isEqualTo(1);
    }

    @Test
    void otherRenewalExceptionsNeverAskForACancelAndTheJobRefusesThem() {
        Sub s = provider(owner);
        clock.setOffset(Duration.between(Instant.now(), s.periodEnd().plusSeconds(60)));
        String in = "in_c_mismatch_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(s.periodEnd()), Map.of(),
                ProviderBilling.renewal(in, s.ref(), "cus_other", priceRef, PriceInterval.MONTH, s.periodEnd(), oneMonth(s.periodEnd()), "pi_" + in)));
        fakeEt.subscriptionOfPayment(in, live(s));
        process(event(in));
        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, in);
        assertThat(cancelJobs(paymentId)).as("R3: the row is current; never cancelled").isZero();
        cancelJob.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId)); // even if one were enqueued: C1
        assertThat(fakeEt.cancelled()).isEmpty();
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, s.id())).isEqualTo("ACTIVE");
    }
}
