package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF R4-16-D1: the per-provider pause (DD §9.2 item 5: ten consecutive transient failures pause that provider's jobs for
 * five minutes) for the multi-provider financial job types, with each job's provider resolved from persisted data. The
 * runner's clock is controlled by the test, so the window is checked exactly, without sleeps. FAKE_ET is provider A,
 * FAKE_US provider B. The pause state is per runner instance (accepted R2-08 O6).
 */
class ProviderPauseTest extends FinancialSuiteBase {

    private static final String US = "FAKE_US";
    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired private JobQueue queue;
    @Autowired private BillingStrategyRegistry registry;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-06-01T00:00:00Z"));
    private final Clock runnerClock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeEach
    void enableUs() {
        outcomes.setBillingEnabled(Market.US, true); // reset to off by the base after each test
    }

    private JobRunner runner() {
        return runner(null, runnerClock);
    }

    /** Polls until nothing more is claimed; returns the number of claims. */
    private int pollAll(JobRunner r) throws Exception {
        int claims = 0;
        for (int guard = 0; guard < 100; guard++) {
            List<Future<?>> started = r.pollOnce();
            if (started.isEmpty()) return claims;
            for (Future<?> f : started) settle(f);
            claims += started.size();
        }
        throw new AssertionError("still claiming after 100 polls");
    }

    /** An FAKE_ET inbox job for a real paid checkout (its outcome depends on the fake's script). */
    private String etInbox() throws Exception {
        Tenant t = data.tenant("pause-et-" + IDS.incrementAndGet());
        String eventId = "evt_pause_" + IDS.incrementAndGet();
        assertThat(deliverSigned(eventId, paid(checkout(t, CheckoutKind.NEW, null), soon())).getStatus()).isEqualTo(200);
        return eventRowId(eventId);
    }

    /** A FAKE_US inbox job (stored as the US webhook would after its signature check). */
    private String usInbox() {
        String eventId = "evt_us_" + IDS.incrementAndGet();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\",\"payment_ref\":\"pay_us_" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
        inbox.store(US, new InboundEvent(eventId, "payment.succeeded"), body);
        return jdbc.queryForObject("select id from provider_event where provider = ? and event_id = ?", String.class, US, eventId);
    }

    private Map<String, Object> inboxJob(String eventRowId) {
        return jdbc.queryForMap("select status, claims, run_at, last_error_kind from job where type = 'inbox.process' and payload->>'providerEventId' = ?", eventRowId);
    }

    private void backoffPasses() {
        jdbc.update("update job set run_at = now() - interval '1 second' where status = 'PENDING' and run_at > now()");
    }

    private void park(String type) {
        jdbc.update("update job set run_at = now() + interval '1 day' where status = 'PENDING' and type = ?", type);
    }

    private void due(String type) {
        jdbc.update("update job set run_at = now() - interval '1 second' where status = 'PENDING' and type = ?", type);
    }

    /** {@code n} FAKE_ET inbox jobs failing transiently, all claimed and failed once. */
    private List<String> etFailures(JobRunner r, int n) throws Exception {
        fakeEt.script(FakeProviderClient.Script.VERIFY_TIMEOUT);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(etInbox());
        assertThat(pollAll(r)).isEqualTo(n);
        for (String id : ids) assertThat(inboxJob(id).get("last_error_kind")).isEqualTo("TRANSIENT");
        return ids;
    }

    // ---- threshold ----

    @Test
    void tenConsecutiveTransientFailuresPauseThatProvider() throws Exception {
        JobRunner r = runner();
        etFailures(r, 10);
        assertThat(r.isPaused(PROVIDER)).isTrue();
        assertThat(r.isPaused(US)).as("provider B is untouched").isFalse();
        evidence("d1.txt", "threshold: 10 consecutive FAKE_ET transient failures -> FAKE_ET paused=true, FAKE_US paused=false");
    }

    @Test
    void nineTransientFailuresDoNotPause() throws Exception {
        JobRunner r = runner();
        etFailures(r, 9);
        assertThat(r.isPaused(PROVIDER)).isFalse();
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        String next = etInbox();
        assertThat(pollAll(r)).as("the next job is claimed normally").isEqualTo(1);
        assertThat(inboxJob(next).get("status")).isEqualTo("DONE");
        evidence("d1.txt", "threshold: 9 failures -> not paused; the next job was claimed and DONE");
    }

    // ---- the five-minute window ----

    @Test
    void aPausedProvidersJobsWaitExactlyFiveMinutesThenResume() throws Exception {
        JobRunner r = runner();
        etFailures(r, 10);
        Instant pausedAt = now.get();
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        String waiting = etInbox();

        now.set(pausedAt.plus(Duration.ofMinutes(5)).minusMillis(1));
        assertThat(r.isPaused(PROVIDER)).isTrue();
        assertThat(pollAll(r)).as("1 ms before the end: nothing claimed").isZero();
        assertThat(inboxJob(waiting)).containsEntry("status", "PENDING").containsEntry("claims", 0);

        now.set(pausedAt.plus(Duration.ofMinutes(5)));
        assertThat(r.isPaused(PROVIDER)).isFalse();
        assertThat(pollAll(r)).as("at five minutes: claimed").isEqualTo(1);
        assertThat(inboxJob(waiting)).containsEntry("status", "DONE").containsEntry("claims", 1);
        evidence("d1.txt", "window: at pause+4:59.999 nothing claimed (job PENDING, claims 0); at pause+5:00 claimed once and DONE");
    }

    /** After the pause, the backed-off payments and a refund held during the pause each run once: no duplicate charge or refund. */
    @Test
    void afterThePauseEveryHeldJobRunsOnceWithoutDuplicates() throws Exception {
        JobRunner r = runner();
        Tenant refunded = data.tenant("pause-refund");
        String rc = checkout(refunded, CheckoutKind.NEW, null);
        checkouts.cancel(refunded.businessId(), rc);
        process(event(paid(rc, soon()))); // row 4: an automatic refund request and its job
        park("refund.execute");
        park("checkout.expire");
        park("inbox.process");
        List<String> failed = etFailures(r, 10);
        assertThat(r.isPaused(PROVIDER)).isTrue();

        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        backoffPasses();
        due("refund.execute");
        assertThat(pollAll(r)).as("paused: no payment re-fetch and no refund submission").isZero();
        assertThat(fakeEt.refundSubmitCalls()).isZero();

        now.set(now.get().plus(Duration.ofMinutes(5)).plusSeconds(1));
        pollAll(r);
        for (String id : failed) assertThat(inboxJob(id)).containsEntry("status", "DONE").containsEntry("claims", 2);
        assertThat(count("select count(*) from payment where provider_payment_ref like 'pay_%' and kind = 'CHARGE' and business_id in (select business_id from checkout_session)"))
                .isGreaterThanOrEqualTo(10);
        assertThat(count("select count(*) from (select provider_payment_ref from payment group by provider_payment_ref, kind having count(*) > 1) d")).as("no duplicate charge").isZero();
        assertThat(fakeEt.refundSubmitCalls()).as("one refund submission").isEqualTo(1);
        assertThat(fakeEt.refundsCreated()).as("one provider refund").isEqualTo(1);
        assertThat(jdbc.queryForObject("select r.status from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, refunded.businessId()))
                .isEqualTo("SUBMITTED");
        assertLedgerAccounted("D1 resume");
        evidence("d1.txt", "resume: 10 backed-off payments each DONE on claim 2, no duplicate charge; refund held during the pause submitted once (1 provider refund)");
    }

    // ---- consecutive counting ----

    @Test
    void aSuccessResetsTheConsecutiveCount() throws Exception {
        JobRunner r = runner();
        etFailures(r, 9);
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        etInbox();
        assertThat(pollAll(r)).isEqualTo(1);
        etFailures(r, 9);
        assertThat(r.isPaused(PROVIDER)).as("9 + success + 9 is not ten consecutive").isFalse();
        etFailures(r, 1);
        assertThat(r.isPaused(PROVIDER)).as("the tenth consecutive failure pauses").isTrue();
        evidence("d1.txt", "reset: 9 failures, 1 success, 9 failures -> not paused; 1 more (10 consecutive) -> paused");
    }

    // ---- isolation ----

    @Test
    void oneProvidersFailuresNeverPauseAnother() throws Exception {
        JobRunner r = runner();
        etFailures(r, 5);
        fakeUs.script(FakeProviderClient.Script.VERIFY_TIMEOUT);
        for (int i = 0; i < 5; i++) usInbox();
        assertThat(pollAll(r)).isEqualTo(5);
        assertThat(r.isPaused(PROVIDER)).as("5 + 5 across two providers pauses neither").isFalse();
        assertThat(r.isPaused(US)).isFalse();

        etFailures(r, 5);
        assertThat(r.isPaused(PROVIDER)).as("FAKE_ET's own tenth").isTrue();
        assertThat(r.isPaused(US)).isFalse();

        fakeUs.script(FakeProviderClient.Script.SUCCESS);
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        String us = usInbox(), et = etInbox();
        assertThat(pollAll(r)).as("only provider B's job is claimed").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from job where type = 'inbox.process' and payload->>'providerEventId' = ?", String.class, us)).isEqualTo("DONE");
        assertThat(inboxJob(et)).containsEntry("status", "PENDING").containsEntry("claims", 0);
        evidence("d1.txt", "isolation: 5 ET + 5 US failures -> neither paused; 5 more ET -> ET paused, US not; while ET paused a US job ran (DONE) and the ET job waited (claims 0)");
    }

    // ---- identity across retries ----

    @Test
    void retriedJobsKeepCountingAgainstTheirOwnProvider() throws Exception {
        JobRunner r = runner();
        List<String> two = etFailures(r, 2);
        for (int round = 2; round <= 5; round++) {
            assertThat(r.isPaused(PROVIDER)).as("after %d failures", (round - 1) * 2).isFalse();
            backoffPasses();
            assertThat(pollAll(r)).isEqualTo(2);
        }
        assertThat(r.isPaused(PROVIDER)).as("2 jobs x 5 attempts = 10 failures of FAKE_ET").isTrue();
        for (String id : two) assertThat(inboxJob(id)).containsEntry("claims", 5).containsEntry("status", "PENDING");
        evidence("d1.txt", "identity: two FAKE_ET jobs retried 5 times each (claims 5) were counted against FAKE_ET each time -> paused at the 10th failure");
    }

    // ---- every financial job type ----

    @Test
    void checkoutAndRefundJobsCountAgainstTheirProviderToo() throws Exception {
        JobRunner r = runner();
        List<String> refundTenants = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Tenant t = data.tenant("pause-type-refund-" + i);
            String c = checkout(t, CheckoutKind.NEW, null);
            checkouts.cancel(t.businessId(), c);
            process(event(paid(c, soon())));
            refundTenants.add(t.businessId());
        }
        for (int i = 0; i < 3; i++) {
            Tenant t = data.tenant("pause-type-expire-" + i);
            checkouts.cancel(t.businessId(), checkout(t, CheckoutKind.NEW, null));
        }
        park("refund.execute");
        park("checkout.expire");
        park("inbox.process");

        etFailures(r, 4); // inbox.process
        fakeEt.script(FakeProviderClient.Script.EXPIRE_TIMEOUT);
        due("checkout.expire");
        assertThat(pollAll(r)).as("six checkout.expire jobs (three from the refund fixtures)").isEqualTo(6);
        assertThat(r.isPaused(PROVIDER)).as("4 + 6 = 10").isTrue();
        assertThat(count("select count(*) from job where type = 'checkout.expire' and last_error_kind = 'TRANSIENT'")).isEqualTo(6);

        // the pause ends and the count starts again: 3 refund.execute + 3 checkout.create + 4 inbox.process = 10 more
        now.set(now.get().plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(r.isPaused(PROVIDER)).isFalse();
        for (int i = 0; i < 3; i++) {
            Tenant t = data.tenant("pause-type-create-" + i);
            checkouts.reserve(t.businessId(), t.owner().getId(), price, "pause-create-" + i, Market.ET, registry.forMarket(Market.ET));
        }
        park("checkout.expire");
        park("inbox.process");
        due("refund.execute");
        due("checkout.create");
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        assertThat(pollAll(r)).isEqualTo(6);
        assertThat(count("select count(*) from job where type = 'refund.execute' and last_error_kind = 'TRANSIENT'")).isEqualTo(3);
        assertThat(count("select count(*) from job where type = 'checkout.create' and last_error_kind = 'TRANSIENT'")).isEqualTo(3);
        assertThat(r.isPaused(PROVIDER)).as("6 of 10").isFalse();
        etFailures(r, 4);
        assertThat(r.isPaused(PROVIDER)).as("3 refund + 3 create + 4 inbox = 10 consecutive FAKE_ET failures").isTrue();
        assertThat(fakeEt.refundsCreated()).as("accepted once per refund key, never twice").isEqualTo(3);
        evidence("d1.txt", "types: 4 inbox.process + 6 checkout.expire -> paused; after the pause 3 refund.execute + 3 checkout.create + 4 inbox.process -> paused again. "
                + "All four financial types count against their own provider");
        assertThat(refundTenants).hasSize(3);
    }

    // ---- concurrency ----

    @Test
    void concurrentPollsOfOneRunnerCannotBypassThePauseOrSpendClaims() throws Exception {
        JobRunner r = runner();
        etFailures(r, 10);
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        List<String> waiting = new ArrayList<>();
        for (int i = 0; i < 6; i++) waiting.add(etInbox());
        backoffPasses();

        CyclicBarrier start = new CyclicBarrier(4);
        List<Future<Integer>> polls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            polls.add(async(() -> {
                start.await(10, TimeUnit.SECONDS);
                return r.pollOnce().size();
            }));
        }
        int claimed = 0;
        for (Future<Integer> f : polls) claimed += join(f);

        assertThat(claimed).as("four concurrent polls claim nothing of the paused provider").isZero();
        for (String id : waiting) assertThat(inboxJob(id)).containsEntry("status", "PENDING").containsEntry("claims", 0);
        assertThat(count("select max(claims) from job where type = 'inbox.process'")).as("the pause spends no claim (claim ceiling intact)").isEqualTo(1);
        JobRunner other = runner(null, runnerClock);
        evidence("d1.txt", "concurrency: 4 concurrent pollOnce on the paused runner claimed 0; 16 waiting jobs kept claims <= 1. Per-instance state (O6): a second runner instance isPaused="
                + other.isPaused(PROVIDER));
    }

    // ---- jobs without a provider ----

    @Test
    void jobsWithoutAProviderRunWhileAProviderIsPaused() throws Exception {
        JobRunner r = runner();
        etFailures(r, 10);
        String bucket = "pause-" + UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s -> queue.enqueue(ReconciliationJobs.HOURLY_TYPE, bucket, new ReconciliationJobs.Payload(bucket)));
        assertThat(pollAll(r)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from job where type = ? and key = ?", String.class, ReconciliationJobs.HOURLY_TYPE, bucket)).isEqualTo("DONE");
        evidence("d1.txt", "no provider: a provider-free job (reconciliation hourly) ran while FAKE_ET was paused");
    }
}
