package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.subscription.EffectiveView;
import com.agafari.com.subscription.PeriodEndJob;
import com.agafari.com.subscription.ScheduledChangeJob;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-16, drills (assessment §4.4, PO E3). <b>Simulated outage and recovery coverage</b>: deterministic integration
 * tests, not process kills. Job time is the database's {@code now()}, so elapsed retry time is produced by moving
 * {@code run_at} back by SQL; business time uses {@code MonetizationClock.setOffset}.
 */
class FinancialDrillTest extends FinancialSuiteBase {

    @Autowired private PeriodEndJob periodEndJob;
    @Autowired private ScheduledChangeJob scheduledChangeJob;
    @Autowired private BusinessSubscriptionRepository subscriptions;

    private void backoffPasses() {
        jdbc.update("update job set run_at = now() - interval '1 second' where status = 'PENDING' and run_at > now()"); // E3: SQL scheduling adjustment
    }

    // ---- provider outage (simulated) ----

    /**
     * Before: a refund is requested and a checkout is being created. Outage: the provider times out on refunds, creation
     * and every payment re-fetch, while ten paid webhooks arrive and reconciliation runs. Recovery: everything is processed
     * once, the refund is submitted once with its original key, and every charge is accounted for.
     */
    @Test
    void providerOutageAndRecovery() throws Exception {
        Map<String, Object> log = new LinkedHashMap<>();
        // AGF R4-16-D1: the runner's own clock, so the provider pause can be passed deterministically
        java.util.concurrent.atomic.AtomicReference<Instant> runnerNow = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        JobRunner runner = runner(null, new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return runnerNow.get(); }
        });

        // a row-4 refund, requested before the outage
        Tenant refunded = data.tenant("outage-refund");
        String rc = checkout(refunded, CheckoutKind.NEW, null);
        checkouts.cancel(refunded.businessId(), rc);
        process(event(paid(rc, soon())));
        String refund = jdbc.queryForObject("select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, refunded.businessId());

        // outage, phase 1: the provider accepts but the answer times out (refund submission and checkout creation)
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        drain(runner, "refund.execute");
        assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, refund)).as("outcome unknown, not resubmitted blindly").isEqualTo("UNKNOWN");
        Tenant buyer = data.tenant("outage-buyer");
        int created = httpStatus(buyer, "outage-create", price);
        log.put("PAY-21 checkout create during outage: HTTP", created);
        assertThat(payments(buyer)).isZero();
        assertThat(current(buyer)).as("no phantom subscription").isNull();

        // outage, phase 2: every payment re-fetch times out
        fakeEt.script(FakeProviderClient.Script.VERIFY_TIMEOUT);
        List<Tenant> payers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Tenant t = data.tenant("outage-" + i);
            String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
            fakeEt.record(new ProviderRecord(ref, RecordType.PAYMENT, soon()));
            assertThat(deliverSigned("evt_outage_" + i, ref).getStatus()).isEqualTo(200);
            payers.add(t);
        }
        int claims = drain(runner, "inbox.process");
        ReconciliationService.DailyResult during = reconciliation.daily(clock.now());
        drainInternalEvents();
        log.put("inbox claims during outage", claims);
        log.put("reconciliation during outage", during);
        for (Tenant t : payers) {
            assertThat(payments(t)).as("nothing recorded from a timed-out re-fetch").isZero();
            assertThat(current(t)).isNull();
        }
        assertThat(count("select count(*) from billing_exception where code like 'RECONCILIATION%'")).as("G1: a timeout is not evidence of missing money").isZero();
        assertThat(count("select count(*) from provider_event where status = 'PENDING' and last_error like 'UNRESOLVED%'")).as("events wait, visibly").isGreaterThanOrEqualTo(10);
        assertThat(count("select count(*) from job where type = 'inbox.process' and status = 'PENDING' and last_error_kind = 'TRANSIENT'")).as("backing off").isGreaterThanOrEqualTo(10);
        assertThat(fakeEt.verifyRanInTransaction() || fakeEt.refundRanInTransaction() || fakeEt.createRanInTransaction()).as("NC-19: no provider call inside a transaction").isFalse();

        // AGF R4-16-D1: ten consecutive transient failures paused the provider (DD §9.2 item 5); nothing is claimed during the pause
        assertThat(runner.isPaused(PROVIDER)).as("D1: the outage paused the provider").isTrue();
        log.put("provider paused during outage", true);

        // recovery, inside the refund idempotency window
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        clock.setOffset(Duration.ofHours(1));
        backoffPasses();
        assertThat(runner.pollOnce()).as("D1: still paused, nothing claimed").isEmpty();
        runnerNow.set(runnerNow.get().plus(Duration.ofMinutes(5)).plusSeconds(1)); // the 5-minute pause ends
        assertThat(runner.isPaused(PROVIDER)).isFalse();
        drain(runner, "inbox.process", "refund.execute");
        reconciliation.hourly(clock.now().plus(15, ChronoUnit.MINUTES)); // the stale CREATING checkout is recovered
        for (Tenant t : payers) {
            assertThat(payments(t)).as("processed once").isEqualTo(1);
            assertThat(periods(t)).isEqualTo(1);
            assertThat(exceptions(t)).isZero();
        }
        assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, refund)).isEqualTo("SUBMITTED");
        assertThat(fakeEt.refundsCreated()).as("one refund at the provider, same key").isEqualTo(1);
        assertThat(count("select count(*) from checkout_session where business_id = ? and status = 'OPEN'", buyer.businessId())).as("the checkout opened after recovery").isEqualTo(1);
        assertThat(payments(buyer)).isZero();
        log.put("recovery", "10 payments processed once; refund SUBMITTED once; checkout opened");
        Map<String, Object> visible = assertReconciliationClean("provider outage recovery");
        evidence("drills.txt", "SIMULATED provider outage and recovery | " + log + " | visible=" + visible);
    }

    /**
     * DD §9.2 item 5 (R2-08): after 10 consecutive transient failures, that provider's jobs are delayed 5 minutes. No
     * financial job handler declares its provider ({@code JobHandler.provider()} is null for {@code inbox.process},
     * {@code refund.execute}, {@code checkout.create} and {@code checkout.expire}), so during an outage the pause never
     * engages. <b>Defect R4-16-D1</b>, reported under PO E2 with this preserved failing test; fix authorization is a
     * separate decision. Run it with {@code -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition}.
     */
    @Test
    void theProviderPauseEngagesAfterTenTransientFailuresDuringAnOutage() throws Exception {
        JobRunner runner = runner(null, null);
        fakeEt.script(FakeProviderClient.Script.VERIFY_TIMEOUT);
        for (int i = 0; i < 12; i++) {
            Tenant t = data.tenant("pause-" + i);
            deliverSigned("evt_pause_" + i, paid(checkout(t, CheckoutKind.NEW, null), soon()));
        }
        drain(runner, "inbox.process");
        int failed = count("select count(*) from job where type = 'inbox.process' and last_error_kind = 'TRANSIENT'");
        evidence("drills.txt", "D1 provider pause: transient inbox failures=" + failed + " paused=" + runner.isPaused(PROVIDER));
        assertThat(failed).isGreaterThanOrEqualTo(10);
        assertThat(runner.isPaused(PROVIDER)).as("DD §9.2 item 5: the provider is paused after 10 consecutive transient failures").isTrue();
    }

    // ---- worker down for an hour (simulated) ----

    @Test
    void workersDownForAnHourThenRestarted() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        Instant start = Instant.now();
        // A: paid on time, its webhook stored while no worker runs; its checkout expires during the downtime
        Tenant a = data.tenant("down-a");
        Instant expiresA = micros(start.plus(30, ChronoUnit.MINUTES));
        String ca = checkout(a, CheckoutKind.NEW, null, price, ET_AMOUNT, expiresA);
        assertThat(deliverSigned("evt_down_a", paid(ca, micros(start.plus(1, ChronoUnit.MINUTES)))).getStatus()).isEqualTo(200);
        // B: a paid row whose period ends during the downtime (no grace); G: one with a 1-day fixture grace (AD-7 open)
        Tenant b = data.tenant("down-b"), g = data.tenant("down-g");
        String subB = paidRow(b, Duration.ofMinutes(20)), subG = paidRow(g, Duration.ofMinutes(20));
        jdbc.update("update business_subscription set grace_days = 1 where id = ?", subG); // explicit fixture grace
        // C: a funded downgrade whose boundary passes during the downtime
        Tenant c = data.tenant("down-c");
        String subC = paidRow(c, Duration.ofMinutes(25));
        String change = paidDowngrade(c, subC);
        assertThat(process(event(paid(downgradeCheckout(c, change, subC), micros(start))))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(changeRow(change).get("funding_payment_id")).isNotNull();
        // D: an unpaid checkout that expires during the downtime
        Tenant d = data.tenant("down-d");
        String cd = checkout(d, CheckoutKind.NEW, null, price, ET_AMOUNT, expiresA);

        clock.setOffset(Duration.ofHours(1)); // one hour passes with every worker down
        Instant now = clock.now();
        Map<String, Object> during = new LinkedHashMap<>();
        during.put("A current", current(a));
        during.put("B", view(subB, now));
        during.put("G", view(subG, now));
        during.put("C from-row", view(subC, now));
        assertThat(current(a)).as("not activated before its verified event is processed").isNull();
        assertThat(view(subB, now).entitledToPlan()).as("MT-05: expired at read time").isFalse();
        assertThat(view(subG, now).entitledToPlan()).as("MT-05: in recorded grace at read time").isTrue();
        assertThat(view(subG, now).status().name()).isEqualTo("PAST_DUE");
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", c.businessId())).as("reads never create the target").isZero();
        assertThat(subRow(subB).get("status")).as("reads never write").isEqualTo("ACTIVE");

        // restart: the cadences enqueue, the runner drains, then everything runs again
        Map<String, Object> first = restart();
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        for (Tenant t : List.of(a, b, g, c, d)) states.put(t.businessId(), businessState(t));

        assertThat(providerSubscriptions(a)).as("A: judged by t_pay, activated although processed after expiry").isEqualTo(1);
        assertThat(exceptions(a)).isZero();
        assertThat(subRow(subB)).containsEntry("status", "EXPIRED");
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action like 'SUBSCRIPTION_%EXPIRED%'", subB)).as("B: persisted once").isLessThanOrEqualTo(1);
        assertThat(subRow(subG)).containsEntry("status", "PAST_DUE");
        assertThat(changeRow(change)).containsEntry("status", "APPLIED");
        assertThat(subRow(subC)).containsEntry("ended_reason", "CHANGED");
        assertThat(checkoutStatus(cd)).isEqualTo("EXPIRED");
        assertThat(view(subB, now).entitledToPlan()).as("the persisted state equals what reads showed").isFalse();

        Map<String, Object> second = restart();
        for (Tenant t : List.of(a, b, g, c, d)) {
            assertThat(businessState(t)).as("a second restart changes nothing for %s", t.businessId()).isEqualTo(states.get(t.businessId()));
        }
        Map<String, Object> visible = assertReconciliationClean("worker down 1 h");
        evidence("drills.txt", "SIMULATED workers down 1 h | during (read time)=" + during + " | restart 1=" + first + " | restart 2=" + second + " | visible=" + visible);
    }

    private EffectiveView view(String subscriptionId, Instant now) {
        return evaluator.view(subscriptions.findById(subscriptionId).orElseThrow(), false, now);
    }

    private Map<String, Object> restart() throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("periodEndDue", periodEndJob.enqueueDue());
        r.put("changesDue", scheduledChangeJob.enqueueDue());
        r.put("hourly", reconciliation.hourly(clock.now()));
        r.put("claims", drain(runner(null, null), "inbox.process", "period-end.persist", "scheduled-change.apply", "refund.execute"));
        return r;
    }
}
