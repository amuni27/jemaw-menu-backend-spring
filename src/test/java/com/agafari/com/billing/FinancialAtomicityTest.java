package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.subscription.PeriodEndJob;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AGF SUB-R4-16, atomicity checks A1–A7 (assessment §5.2, PO E4) and the direct restaurant-lock check (requirement 1).
 *
 * <p>Each check installs a fault trigger on the disposable database that refuses one <b>later</b> write of the unit. The
 * trigger's hit counter proves the write was reached, and its witness (a query evaluated inside the doomed transaction)
 * proves the earlier writes had happened. The test then asserts that no business state of the restaurant was committed,
 * removes the fault and shows the same unit completing once. Every fault is dropped after each test, failed or not.
 */
class FinancialAtomicityTest extends FinancialSuiteBase {

    @Autowired private PeriodEndJob periodEndJob;

    @BeforeEach
    void noFaultLeftBehindByAnEarlierTest() {
        assertThat(faultObjectsLeft()).as("E4: cleanup ran after the previous test, whatever its result").isZero();
    }

    private void record(String check, Fault f, String witness, Map<String, Object> before, Map<String, Object> after) {
        evidence("atomicity.txt", check + " | fault hits=" + f.hits() + " | witness inside the refused transaction=" + witness + " | business state unchanged=" + before.equals(after));
    }

    private static String cond(Tenant t) {
        return "new.business_id = '" + t.businessId() + "'";
    }

    // ---- A1: charge + invoice + period + subscription replacement ----

    @Test
    void a1APeriodRefusedAfterTheReplacementLeavesNoChargeInvoiceOrReplacement() throws Exception {
        Tenant t = data.tenant("a1");
        startOwnerFree(t);
        String free = current(t);
        String c = checkout(t, CheckoutKind.UPGRADE, free, price, ET_AMOUNT, Instant.now().plus(23, ChronoUnit.HOURS));
        String e = event(paid(c, soon()));
        Fault f = fault("a1", "subscription_period", "insert", cond(t),
                "(select count(*) from payment where business_id = new.business_id) || '/' || (select count(*) from billing_invoice where business_id = new.business_id) || '/' "
                        + "|| (select status from business_subscription where id = '" + free + "') || '/' || (select count(*) from business_subscription where business_id = new.business_id)");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(thrown).as("the unit failed").isNotNull();
        assertThat(f.hits()).as("the period write was reached").isEqualTo(1);
        String witness = f.witness(thrown);
        assertThat(witness).as("charge 1 / invoice 1 / old row EXPIRED / two rows, inside the transaction").isEqualTo("1/1/EXPIRED/2");
        Map<String, Object> after = businessState(t);
        assertThat(after).as("nothing committed").isEqualTo(before);
        assertThat(eventRow(e).get("status")).as("the event stays pending for a retry").isEqualTo("PENDING");
        record("A1 charge+invoice+replacement+period (fault on subscription_period insert)", f, witness, before, after);

        f.remove();
        assertThat(process(e)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periods(t)).isEqualTo(1);
        assertThat(subRow(free)).containsEntry("status", "EXPIRED");
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void a1bTheLastWriteOfTheOutcomeRefusedLeavesNothing() throws Exception {
        Tenant t = data.tenant("a1b");
        String c = checkout(t, CheckoutKind.NEW, null);
        String e = event(paid(c, soon()));
        Fault f = fault("a1b", "provider_event", "update", "new.id = '" + e + "' and new.status = 'PROCESSED'",
                "(select count(*) from subscription_period where business_id = '" + t.businessId() + "') || '/' || (select status from checkout_session where id = '" + c + "')");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(f.hits()).isEqualTo(1);
        String witness = f.witness(thrown);
        assertThat(witness).as("period written and checkout completed, inside the transaction").isEqualTo("1/COMPLETED");
        Map<String, Object> after = businessState(t);
        assertThat(after).isEqualTo(before);
        record("A1b whole outcome (fault on the final provider_event update)", f, witness, before, after);

        f.remove();
        assertThat(process(e)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periods(t)).isEqualTo(1);
    }

    // ---- A2: charge + STATE_CHANGED exception (row 6; N5) ----

    @Test
    void a2ARow6ExceptionRefusedLeavesNoCharge() throws Exception {
        Tenant t = data.tenant("a2");
        startOwnerFree(t);
        String e = event(paid(checkout(t, CheckoutKind.NEW, null), soon())); // row 6: a current row appeared
        Fault f = fault("a2", "billing_exception", "insert", cond(t), "(select count(*) from payment where business_id = new.business_id)");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(f.hits()).isEqualTo(1);
        assertThat(f.witness(thrown)).as("the charge was written first").isEqualTo("1");
        Map<String, Object> after = businessState(t);
        assertThat(after).isEqualTo(before);
        record("A2 charge+STATE_CHANGED exception, row 6 (fault on billing_exception insert)", f, f.witness(thrown), before, after);

        f.remove();
        process(e);
        assertThat(onlyException(t)).containsEntry("code", PaymentOutcome.STATE_CHANGED);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void a2bAPaymentAfterTheDowngradeDeadlineRefusedLeavesNoCharge() throws Exception {
        Tenant t = data.tenant("a2b");
        String sub = paidRow(t, Duration.ofMinutes(5));
        String change = paidDowngrade(t, sub);
        String co = downgradeCheckout(t, change, sub);
        String e = event(paid(co, periodEnd(sub).plusSeconds(1))); // N5: after the deadline, the old row still current
        Fault f = fault("a2b", "billing_exception", "insert", cond(t), "(select count(*) from payment where business_id = new.business_id)");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(f.hits()).isEqualTo(1);
        assertThat(f.witness(thrown)).as("the paid row's charge and the late charge").isEqualTo("2");
        Map<String, Object> after = businessState(t);
        assertThat(after).isEqualTo(before);
        record("A2b charge+STATE_CHANGED exception, N5 late downgrade funding (fault on billing_exception insert)", f, f.witness(thrown), before, after);

        f.remove();
        process(e);
        assertThat(onlyException(t)).containsEntry("code", PaymentOutcome.STATE_CHANGED);
        assertThat(changeRow(change).get("funding_payment_id")).isNull();
    }

    // ---- A3: funding + audit + checkout COMPLETED ----

    @Test
    void a3FundingRefusedAtCheckoutCompletionLeavesTheChangeUnfunded() throws Exception {
        Tenant t = data.tenant("a3");
        String sub = paidRow(t, Duration.ofMinutes(5));
        String change = paidDowngrade(t, sub);
        String co = downgradeCheckout(t, change, sub);
        String e = event(paid(co, soon().minusSeconds(50)));
        Fault f = fault("a3", "checkout_session", "update", "new.id = '" + co + "' and new.status = 'COMPLETED'",
                "(select coalesce(funding_payment_id, 'none') <> 'none' from scheduled_change where id = '" + change + "')");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(f.hits()).isEqualTo(1);
        assertThat(f.witness(thrown)).as("funded inside the transaction (PostgreSQL boolean text)").isEqualTo("t");
        Map<String, Object> after = businessState(t);
        assertThat(after).isEqualTo(before);
        // the SCHEDULED_CHANGE_FUNDED audit joins the transaction through JPA and is flushed at commit, so the in-transaction
        // witness can't see it; its rollback is shown by the unchanged business audit map
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'SCHEDULED_CHANGE_FUNDED'", change)).isZero();
        assertThat(changeRow(change).get("funding_payment_id")).isNull();
        record("A3 funding+audit+checkout COMPLETED (fault on checkout_session update)", f, f.witness(thrown), before, after);

        f.remove();
        process(e);
        assertThat(changeRow(change).get("funding_payment_id")).isNotNull();
        assertThat(checkoutStatus(co)).isEqualTo("COMPLETED");
    }

    // ---- A4: activation + supersession + released-charge exception (the R4-15 deferred supersession control) ----

    @Test
    void a4ASupersessionReviewRefusedLeavesTheActivationAndTheHeldChangeUntouched() throws Exception {
        Tenant t = data.tenant("a4");
        String sub = paidRow(t, Duration.ofHours(1));
        String change = paidDowngrade(t, sub);
        String co = downgradeCheckout(t, change, sub);
        process(event(paid(co, soon().minusSeconds(50))));
        String held = (String) changeRow(change).get("funding_payment_id");
        assertThat(held).isNotNull();
        String price4b = price("MODEL_4", "ET", "ETB", 3000);
        String upgrade = checkout(t, CheckoutKind.UPGRADE, sub, price4b, 3000, Instant.now().plus(23, ChronoUnit.HOURS));
        String e = event(paid(upgrade, soon()));
        Fault f = fault("a4", "billing_exception", "insert", cond(t) + " and new.payment_id = '" + held + "'",
                "(select status from scheduled_change where id = '" + change + "') || '/' || (select status from business_subscription where id = '" + sub + "') || '/' "
                        + "|| (select count(*) from subscription_period where business_id = new.business_id)");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(f.hits()).isEqualTo(1);
        assertThat(f.witness(thrown)).as("superseded, old row ended, upgrade period written, inside the transaction").isEqualTo("SUPERSEDED/EXPIRED/2");
        Map<String, Object> after = businessState(t);
        assertThat(after).as("no activation without its supersession review").isEqualTo(before);
        assertThat(changeRow(change)).containsEntry("status", "PENDING").containsEntry("funding_payment_id", held);
        assertThat(current(t)).isEqualTo(sub);
        record("A4 activation+supersession+released-charge exception (fault on the release exception insert)", f, f.witness(thrown), before, after);

        f.remove();
        process(e);
        assertThat(changeRow(change).get("status")).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, held)).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertLedgerAccounted("A4 after recovery");
    }

    // ---- A5: refund request + exception link + job enqueue ----

    @Test
    void a5ARefundExceptionRefusedLeavesNoRefundRequestOrJob() throws Exception {
        Tenant t = data.tenant("a5");
        String c = checkout(t, CheckoutKind.NEW, null);
        checkouts.cancel(t.businessId(), c);
        String e = event(paid(c, soon())); // row 4: an automatic refund
        Fault f = fault("a5", "billing_exception", "insert", cond(t),
                "(select count(*) from refund_request r join payment p on p.id = r.payment_id where p.business_id = new.business_id) || '/' "
                        + "|| (select count(*) from job where type = 'refund.execute' and key = new.refund_request_id)");
        Map<String, Object> before = businessState(t);
        int refundJobs = count("select count(*) from job where type = 'refund.execute'");

        Throwable thrown = catchThrowable(() -> process(e));

        assertThat(f.hits()).isEqualTo(1);
        assertThat(f.witness(thrown)).as("refund request and its job written first").isEqualTo("1/1");
        Map<String, Object> after = businessState(t);
        assertThat(after).isEqualTo(before);
        assertThat(count("select count(*) from job where type = 'refund.execute'")).as("no orphan refund job").isEqualTo(refundJobs);
        record("A5 refund request+job+exception link (fault on billing_exception insert)", f, f.witness(thrown), before, after);

        f.remove();
        process(e);
        assertThat(refunds(t)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    // ---- A6: inbox event + its job (also PAY-22: the database refuses at intake) ----

    @Test
    void a6AnInboxJobRefusedLeavesNoEventAndTheProviderRedeliversIt() throws Exception {
        Tenant t = data.tenant("a6");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        Fault f = fault("a6", "job", "insert", "new.type = 'inbox.process' and new.key = '" + PROVIDER + ":evt_a6'",
                "(select count(*) from provider_event where event_id = 'evt_a6')");

        MockHttpServletResponse first = null;
        Throwable thrown = null;
        try {
            first = deliverSigned("evt_a6", ref);
        } catch (Exception e) {
            thrown = e;
        }

        assertThat(f.hits()).isEqualTo(1);
        String witness = f.witness(thrown != null ? thrown : new RuntimeException(first.getContentAsString()));
        if (witness != null) assertThat(witness).as("the event row was written first").isEqualTo("1");
        assertThat(thrown != null || first.getStatus() >= 500).as("not acknowledged, so the provider retries").isTrue();
        assertThat(eventRowId("evt_a6")).as("nothing stored").isNull();
        record("A6 inbox event+job (fault on job insert), response=" + (first == null ? "exception " + thrown.getClass().getSimpleName() : first.getStatus()), f, witness,
                Map.of(), Map.of());

        f.remove();
        assertThat(deliverSigned("evt_a6", ref).getStatus()).as("the provider's redelivery is stored").isEqualTo(200);
        assertThat(count("select count(*) from job where type = 'inbox.process' and key = ?", PROVIDER + ":evt_a6")).isEqualTo(1);
        process(eventRowId("evt_a6"));
        assertThat(periods(t)).isEqualTo(1);
    }

    // ---- A7: lifecycle change + audit ----

    @Test
    void a7AnExpiryWhoseAuditIsRefusedLeavesTheRowActive() throws Exception {
        Tenant t = data.tenant("a7");
        evaluator.setPeriodEndEnabled(true);
        String sub = paidRow(t, Duration.ofSeconds(30));
        clock.setOffset(Duration.ofMinutes(2));
        Fault f = fault("a7", "admin_audit_event", "insert", "new.target_id = '" + sub + "'", "(select status from business_subscription where id = '" + sub + "')");
        Map<String, Object> before = businessState(t);

        Throwable thrown = catchThrowable(() -> periodEndJob.handle(new PeriodEndJob.Payload(t.businessId(), sub, periodEnd(sub))));

        assertThat(f.hits()).isEqualTo(1);
        assertThat(f.witness(thrown)).as("the row was already expired inside the transaction").isEqualTo("EXPIRED");
        Map<String, Object> after = businessState(t);
        assertThat(after).isEqualTo(before);
        assertThat(subRow(sub).get("status")).isEqualTo("ACTIVE");
        record("A7 lifecycle change+audit (fault on admin_audit_event insert)", f, f.witness(thrown), before, after);

        f.remove();
        periodEndJob.handle(new PeriodEndJob.Payload(t.businessId(), sub, periodEnd(sub)));
        assertThat(subRow(sub).get("status")).isEqualTo("EXPIRED");
        assertThat(count("select count(*) from admin_audit_event where target_id = ?", sub)).as("one audit for the expiry, besides the activation's").isEqualTo(2);
    }

    // ---- requirement 1: the restaurant lock, directly ----

    /**
     * Holds only the restaurant row lock (not the configuration lock): a payment for that restaurant must wait for it and
     * commit nothing until it is released. NC-06 (no restaurant lock) makes this fail.
     */
    @Test
    void aPaymentWaitsForTheRestaurantLockAndCommitsNothingWhileItIsHeld() throws Exception {
        Tenant t = data.tenant("lock-direct");
        String e = event(paid(checkout(t, CheckoutKind.NEW, null), soon()));
        Gate gate = restaurantGate(t);

        Future<PaymentOutcomeService.Result> attempt = async(() -> process(e));
        List<Map<String, Object>> waiting = gate.awaitBlocked(1, "direct restaurant lock: payment processing");

        assertThat(waiting.get(0).get("query").toString()).as("it waits on the restaurant row lock").contains("from business where id");
        sleep(200);
        assertThat(payments(t)).as("nothing committed while the restaurant lock is held").isZero();
        assertThat(attempt.isDone()).isFalse();
        gate.close();
        assertThat(join(attempt)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(periods(t)).isEqualTo(1);
    }

    /** The same for a refund execution, which takes only the restaurant lock. */
    @Test
    void aRefundSubmissionWaitsForTheRestaurantLock() throws Exception {
        Tenant t = data.tenant("lock-refund");
        String c = checkout(t, CheckoutKind.NEW, null);
        checkouts.cancel(t.businessId(), c);
        process(event(paid(c, soon())));
        String refund = jdbc.queryForObject("select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, t.businessId());
        RefundService refunds = context.getBean(RefundService.class);
        Gate gate = restaurantGate(t);

        Future<RefundService.Outcome> attempt = async(() -> refunds.execute(refund));
        gate.awaitBlocked(1, "direct restaurant lock: refund submission");
        sleep(200);
        assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, refund)).as("not claimed while the lock is held").isEqualTo("REQUESTED");
        assertThat(fakeEt.refundSubmitCalls()).isZero();
        gate.close();
        assertThat(join(attempt)).isEqualTo(RefundService.Outcome.SUBMITTED);
    }
}
