package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.subscription.EffectiveView;
import com.agafari.com.subscription.PeriodEndJob;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R4-16, the MT-02 to MT-05 consolidation (assessment §4.2) and R12. The existing per-story tests stay where they
 * are; this adds the cross-cutting runs: real job runners, real financial handlers, stale workers (handler attempts are
 * counted separately from committed financial effects, requirement 3) and read-time expiry with every job stopped.
 */
class FinancialConsolidationTest extends FinancialSuiteBase {

    @Autowired private RefundService refundService;
    @Autowired private PeriodEndJob periodEndJob;
    @Autowired private BusinessSubscriptionRepository subscriptions;

    private void expireLeases() {
        jdbc.update("update job set lease_until = now() - interval '1 second' where status = 'RUNNING'");
    }

    private void backoffPasses() {
        jdbc.update("update job set run_at = now() - interval '1 second' where status = 'PENDING' and run_at > now()");
    }

    private Map<String, Object> job(String type) {
        return jdbc.queryForMap("select status, claims, claim_token, last_error, last_error_kind from job where type = ? order by created_at desc limit 1", type);
    }

    private static void await(CountDownLatch l) {
        try {
            if (!l.await(30, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void finish(List<Future<?>> futures) {
        for (Future<?> f : futures) settle(f);
    }

    private HikariDataSource pool() throws Exception {
        HikariDataSource p = new HikariDataSource();
        try (var c = dataSource.getConnection()) {
            p.setJdbcUrl(c.getMetaData().getURL());
        }
        p.setUsername(System.getenv().getOrDefault("AGAFARI_TEST_DB_USERNAME", "postgres"));
        p.setPassword(System.getenv().getOrDefault("AGAFARI_TEST_DB_PASSWORD", "postgres"));
        p.setMaximumPoolSize(4);
        return p;
    }

    /** A row-4 refund request with its AUTO_HANDLING exception and its queued job. */
    private String requestedRefund(Tenant t) {
        String c = checkout(t, CheckoutKind.NEW, null);
        checkouts.cancel(t.businessId(), c);
        process(event(paid(c, soon())));
        return jdbc.queryForObject("select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, t.businessId());
    }

    private String refundStatus(String id) {
        return jdbc.queryForObject("select status from refund_request where id = ?", String.class, id);
    }

    // ---- MT-02: payment-evidence uniqueness ----

    @Test
    void mt02TheDatabaseRefusesASecondPeriodOrFundingFromOnePayment() {
        Tenant t = data.tenant("mt02-db");
        String sub = paidRow(t, Duration.ofHours(1));
        Map<String, Object> period = jdbc.queryForMap("select * from subscription_period where subscription_id = ?", sub);
        Throwable twice = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "insert into subscription_period (id, subscription_id, business_id, period_start, period_end, invoice_id, payment_id) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), sub, t.businessId(), period.get("period_start"), period.get("period_end"), period.get("invoice_id"), period.get("payment_id")));
        assertThat(twice).as("a payment consumed twice is refused").isNotNull();
        // the same dates are reused so the period guard accepts the row; the refusal must come from the payment (or invoice)
        // uniqueness itself, not from another rule such as one period start per subscription (NC-13, first control run)
        assertThat(twice.getMessage()).as("refused by the payment or invoice uniqueness").containsAnyOf("uk_subscription_period_payment", "uk_subscription_period_invoice");
        evidence("consolidation.txt", "MT-02 database: second period from one payment refused: " + twice.getMessage().replaceAll("\\s+", " ").replaceAll(".*ERROR: ", "ERROR: "));
        String change = paidDowngrade(t, sub);
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set funding_payment_id = ?, funding_invoice_id = ?, funded_at = now() where id = ?",
                period.get("payment_id"), period.get("invoice_id"), change)).as("an activation's payment can't fund a change").isNotNull();
        evidence("consolidation.txt", "MT-02 database: a second period from one payment and a funding from a consumed payment are both refused");
    }

    @Test
    void mt02TwoRunnersOnSeparatePoolsApplyOnePaymentOnce() throws Exception {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (int round = 0; round < 5; round++) {
            Tenant t = data.tenant("mt02-two-" + round);
            String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
            deliverSigned("evt_mt02_" + round, ref);
            inbox.storeInternal(PROVIDER, ref);
            try (HikariDataSource p1 = pool(); HikariDataSource p2 = pool()) {
                JobRunner w1 = runner(new JdbcTemplate(p1), null), w2 = runner(new JdbcTemplate(p2), null);
                Gate gate = configGate();
                List<Future<?>> attempts = new ArrayList<>(join(async(w1::pollOnce)));
                attempts.addAll(join(async(w2::pollOnce)));
                assertThat(attempts).hasSize(2);
                gate.awaitBlocked(2, "MT-02 two runners, webhook + reconciliation, round " + round);
                gate.close();
                finish(attempts);
            }
            assertThat(payments(t)).isEqualTo(1);
            assertThat(periods(t)).isEqualTo(1);
            assertThat(exceptions(t)).isZero();
            tally.merge(jdbc.queryForObject("select verification from payment where provider_payment_ref = ?", String.class, ref), 1, Integer::sum);
        }
        evidence("consolidation.txt", "MT-02 two runners, separate pools, webhook + reconciliation: 1 payment and 1 period each round; recorded by " + tally);
    }

    // ---- MT-03: stale workers, attempts vs committed effects ----

    /** The stale worker resumes after the new owner committed: its attempt reaches the provider, but it commits nothing. */
    @Test
    void mt03AStaleInboxWorkerThatResumesLateCommitsNoEffectAndNoJobState() throws Exception {
        Tenant t = data.tenant("mt03-late");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_mt03_late", ref);
        CountDownLatch inFetch = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        fakeEt.onVerify(() -> {
            if (fetches.incrementAndGet() == 1) {
                inFetch.countDown();
                await(release);
            }
        });
        JobRunner w1 = runner(null, null), w2 = runner(null, null);

        List<Future<?>> stale = w1.pollOnce();
        await(inFetch);
        expireLeases();
        assertThat(drain(w2, "inbox.process")).as("the new owner re-claims").isEqualTo(1);
        Map<String, Object> afterOwner = businessState(t);
        assertThat(payments(t)).isEqualTo(1);
        release.countDown();
        finish(stale);

        assertThat(fetches.get()).as("attempts: both handlers reached the provider").isEqualTo(2);
        assertThat(businessState(t)).as("committed effects: only the owner's").isEqualTo(afterOwner);
        assertThat(periods(t)).isEqualTo(1);
        assertThat(job("inbox.process")).containsEntry("status", "DONE").containsEntry("claims", 2).containsEntry("last_error", null);
        evidence("consolidation.txt", "MT-03 stale inbox worker resumes late | attempts=2 (provider re-fetches) | committed effects: 1 payment, 1 period, business state unchanged by the stale attempt | job DONE claims=2, stale completion fenced");
    }

    /** The stale worker resumes while the new owner is still running: one effect, and the stale completion can't finish the owner's claim. */
    @Test
    void mt03AStaleInboxWorkerThatCommitsFirstCannotFinishTheNewOwnersClaim() throws Exception {
        Tenant t = data.tenant("mt03-first");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_mt03_first", ref);
        CountDownLatch inFetch1 = new CountDownLatch(1), release1 = new CountDownLatch(1), inFetch2 = new CountDownLatch(1), release2 = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        fakeEt.onVerify(() -> {
            int n = fetches.incrementAndGet();
            if (n == 1) {
                inFetch1.countDown();
                await(release1);
            } else if (n == 2) {
                inFetch2.countDown();
                await(release2);
            }
        });
        JobRunner w1 = runner(null, null), w2 = runner(null, null);

        List<Future<?>> stale = w1.pollOnce();
        await(inFetch1);
        expireLeases();
        List<Future<?>> owner = w2.pollOnce();
        await(inFetch2);
        Object ownerToken = job("inbox.process").get("claim_token");
        release1.countDown();
        finish(stale);

        assertThat(payments(t)).as("the stale attempt's effect is the single, idempotent one").isEqualTo(1);
        assertThat(job("inbox.process")).as("the stale completion was fenced: the owner's claim stands").containsEntry("status", "RUNNING").containsEntry("claim_token", ownerToken);
        Map<String, Object> afterStale = businessState(t);
        release2.countDown();
        finish(owner);

        assertThat(businessState(t)).as("the owner's attempt adds nothing").isEqualTo(afterStale);
        assertThat(job("inbox.process")).containsEntry("status", "DONE").containsEntry("claims", 2);
        evidence("consolidation.txt", "MT-03 stale inbox worker commits first | attempts=" + fetches.get() + " | committed effects: 1 payment (event row lock + status) | stale job completion fenced: owner's claim stayed RUNNING with its token, then DONE");
    }

    @Test
    void mt03AStaleRefundWorkerNeverMakesASecondRefund() throws Exception {
        Tenant t = data.tenant("mt03-refund");
        String refund = requestedRefund(t);
        CountDownLatch inSubmit = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger submits = new AtomicInteger();
        fakeEt.onRefund(() -> {
            if (submits.incrementAndGet() == 1) {
                inSubmit.countDown();
                await(release);
            }
        });
        JobRunner w1 = runner(null, null), w2 = runner(null, null);

        List<Future<?>> stale = w1.pollOnce();
        await(inSubmit);
        assertThat(refundStatus(refund)).as("the key was committed before the call").isEqualTo("SUBMITTING");
        expireLeases();
        drain(w2, "refund.execute");
        release.countDown();
        finish(stale);

        assertThat(fakeEt.refundSubmitCalls()).as("attempts: two submissions with the same key").isEqualTo(2);
        assertThat(fakeEt.refundsCreated()).as("committed effect: one refund at the provider").isEqualTo(1);
        assertThat(refundStatus(refund)).isEqualTo("SUBMITTED");
        assertThat(count("select count(*) from refund_request where payment_id = (select payment_id from refund_request where id = ?)", refund)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from billing_exception where refund_request_id = ?", String.class, refund)).as("unsettled, still visible").isEqualTo("AUTO_HANDLING");
        evidence("consolidation.txt", "MT-03 stale refund worker | attempts=2 provider submissions (same key) | committed effects: 1 provider refund, 1 refund_request SUBMITTED (unsettled, visible)");
    }

    @Test
    void mt03AStaleExpiryWorkerPersistsTheExpiryOnce() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        Tenant t = data.tenant("mt03-expiry");
        String sub = paidRow(t, Duration.ofSeconds(30));
        clock.setOffset(Duration.ofMinutes(2));
        assertThat(periodEndJob.enqueueDue()).isGreaterThanOrEqualTo(1);
        JobRunner w1 = runner(null, null), w2 = runner(null, null);
        Gate gate = restaurantGate(t);

        List<Future<?>> stale = w1.pollOnce();
        gate.awaitBlocked(1, "MT-03 stale expiry worker (first claim waits)");
        expireLeases();
        List<Future<?>> owner = w2.pollOnce();
        assertThat(owner).hasSize(1);
        gate.awaitBlocked(2, "MT-03 stale expiry worker (both claims wait)");
        gate.close();
        finish(stale);
        finish(owner);

        assertThat(subRow(sub).get("status")).isEqualTo("EXPIRED");
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action <> 'SUBSCRIPTION_PAID_ACTIVATED'", sub)).as("committed effects: one expiry audit").isEqualTo(1);
        assertThat(job("period-end.persist")).containsEntry("status", "DONE").containsEntry("claims", 2);
        evidence("consolidation.txt", "MT-03 stale expiry worker | attempts=2 handler runs (both proven waiting) | committed effects: 1 expiry, 1 audit | job DONE claims=2");
    }

    // ---- MT-04: refund unknown outcome, through the job runner ----

    private int submits0, created0;

    /** A new MT-04 case: default script and capabilities, counters measured from here (refund numbering is kept, as at a real provider). */
    private void fresh() {
        clock.reset();
        fakeEt.script(FakeProviderClient.Script.SUCCESS).capabilities(FakeProviderClient.ALL).laggedLookups(0);
        submits0 = fakeEt.refundSubmitCalls();
        created0 = fakeEt.refundsCreated();
    }

    @Test
    void mt04RefundUnknownOutcomeCasesThroughTheJobRunner() throws Exception {
        Map<String, String> results = new LinkedHashMap<>();
        JobRunner runner = runner(null, null);

        // a: timeout after the provider accepted; retried within the window with the same key
        fresh();
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String a = requestedRefund(data.tenant("mt04-a"));
        drain(runner, "refund.execute");
        assertThat(refundStatus(a)).isEqualTo("UNKNOWN");
        clock.setOffset(Duration.ofHours(1));
        backoffPasses();
        drain(runner, "refund.execute");
        assertThat(refundStatus(a)).as("job %s", job("refund.execute")).isEqualTo("SUBMITTED");
        assertThat(fakeEt.refundsCreated() - created0).isEqualTo(1);
        results.put("a", "UNKNOWN -> same-key retry within window -> SUBMITTED; provider refunds=" + (fakeEt.refundsCreated() - created0));

        // b: the retry would fall after the window: never resubmitted, handed back
        clock.reset();
        fresh();
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String b = requestedRefund(data.tenant("mt04-b"));
        drain(runner, "refund.execute");
        fakeEt.script(FakeProviderClient.Script.LOOKUP_LAG).laggedLookups(100);
        clock.setOffset(Duration.ofHours(25));
        backoffPasses();
        drain(runner, "refund.execute");
        assertThat(fakeEt.refundSubmitCalls() - submits0).isEqualTo(1);
        assertThat(refundStatus(b)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select status from billing_exception where refund_request_id = ?", String.class, b)).isEqualTo("OPEN");
        results.put("b", "after the window: no resubmission, handed back OPEN; submissions=1");

        // c: a crash after SUBMITTING with an empty lookup: never resubmitted
        clock.reset();
        fresh();
        Tenant tc = data.tenant("mt04-c");
        String c = requestedRefund(tc);
        jdbc.update("update refund_request set status = 'SUBMITTING', idempotency_key = ?, submitted_at = ? where id = ?", "refund:" + c, Timestamp.from(clock.now()), c);
        fakeEt.script(FakeProviderClient.Script.LOOKUP_LAG).laggedLookups(100);
        clock.setOffset(Duration.ofHours(25));
        drain(runner, "refund.execute");
        assertThat(fakeEt.refundSubmitCalls() - submits0).isZero();
        assertThat(refundStatus(c)).isEqualTo("UNKNOWN");
        results.put("c", "crash after SUBMITTING, empty lookup: no submission, handed back");

        // d: the lookup finds the refund: recorded without a submission
        clock.reset();
        fresh();
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String d = requestedRefund(data.tenant("mt04-d"));
        drain(runner, "refund.execute");
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        clock.setOffset(Duration.ofHours(25));
        backoffPasses();
        drain(runner, "refund.execute");
        assertThat(refundStatus(d)).isEqualTo("SUBMITTED");
        assertThat(fakeEt.refundSubmitCalls() - submits0).isEqualTo(1);
        results.put("d", "lookup found it: SUBMITTED without a second submission (settlement portion BLOCKED)");

        // e: a definite rejection: handed back, never retried
        clock.reset();
        fresh();
        String e = requestedRefund(data.tenant("mt04-e"));
        fakeEt.script(FakeProviderClient.Script.DECLINE);
        drain(runner, "refund.execute");
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        backoffPasses();
        drain(runner, "refund.execute");
        assertThat(refundStatus(e)).isEqualTo("FAILED_DEFINITE");
        assertThat(fakeEt.refundSubmitCalls() - submits0).isEqualTo(1);
        results.put("e", "definite rejection: FAILED_DEFINITE, OPEN review, no retry");

        // f: no confirmed idempotent refund: nothing submitted
        fresh();
        String f = requestedRefund(data.tenant("mt04-f"));
        fakeEt.capabilities(new ProviderCapabilities(true, true, false, Duration.ofHours(24), true));
        drain(runner, "refund.execute");
        assertThat(fakeEt.refundSubmitCalls() - submits0).isZero();
        assertThat(jdbc.queryForObject("select status from billing_exception where refund_request_id = ?", String.class, f)).isEqualTo("OPEN");
        results.put("f", "capability withdrawn: no submission, handed back for a manual refund");

        // g: two concurrent requests for one payment: one refund request
        fresh();
        Tenant tg = data.tenant("mt04-g");
        String first = requestedRefund(tg);
        String payment = jdbc.queryForObject("select payment_id from refund_request where id = ?", String.class, first);
        List<Integer> created = race(4, i -> () -> {
            String r = new TransactionTemplate(transactions).execute(s -> {
                locks.configShared();
                locks.restaurant(tg.businessId());
                try {
                    return refundService.request(tg.businessId(), payment, "PAID_AFTER_CANCEL", i % 2 == 0 ? "outcome:PAID_AFTER_CANCEL" : "another-key-" + i);
                } catch (IllegalStateException refused) {
                    return null;
                }
            });
            return r != null && !r.equals(first) ? 1 : 0;
        });
        assertThat(created.stream().mapToInt(Integer::intValue).sum()).isZero();
        assertThat(count("select count(*) from refund_request where payment_id = ?", payment)).isEqualTo(1);
        results.put("g", "4 concurrent requests: one refund request");

        assertThat(count("select count(*) from refund_request where status in ('SUCCEEDED', 'FAILED')")).as("no settlement: BLOCKED").isZero();
        evidence("consolidation.txt", "MT-04 through the job runner | " + results);
    }

    // ---- MT-05: read-time expiry with every job stopped ----

    @Test
    void mt05ProviderRowsAndAHeldDowngradeWithEveryJobStopped() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        Tenant graceT = data.tenant("mt05-grace"), plain = data.tenant("mt05-plain"), held = data.tenant("mt05-held");
        String inGrace = paidRow(graceT, Duration.ofMinutes(10));
        jdbc.update("update business_subscription set grace_days = 1 where id = ?", inGrace); // fixture grace (AD-7 open)
        String noGrace = paidRow(plain, Duration.ofMinutes(10));
        String from = paidRow(held, Duration.ofMinutes(10));
        String change = paidDowngrade(held, from);
        process(event(paid(downgradeCheckout(held, change, from), soon().minusSeconds(50))));
        Instant end = periodEnd(inGrace), graceUntil = end.plus(Duration.ofDays(1));

        Map<String, Object> views = new LinkedHashMap<>();
        Map<String, Object> before = Map.of("g", businessState(graceT), "p", businessState(plain), "h", businessState(held));
        EffectiveView inGraceView = view(inGrace, end.plusSeconds(1));
        EffectiveView atGraceUntil = view(inGrace, graceUntil);
        EffectiveView justBefore = view(inGrace, graceUntil.minusNanos(1000));
        EffectiveView plainAfter = view(noGrace, periodEnd(noGrace));
        EffectiveView heldAfter = view(from, periodEnd(from).plusSeconds(1));
        views.put("PROVIDER in grace", inGraceView);
        views.put("PROVIDER 1 µs before graceUntil", justBefore);
        views.put("PROVIDER at graceUntil", atGraceUntil);
        views.put("PROVIDER no grace at period_end", plainAfter);
        views.put("funded downgrade's row after its boundary, before any job", heldAfter);
        assertThat(inGraceView.entitledToPlan()).isTrue();
        assertThat(inGraceView.status().name()).isEqualTo("PAST_DUE");
        assertThat(justBefore.entitledToPlan()).isTrue();
        assertThat(atGraceUntil.entitledToPlan()).isFalse();
        assertThat(plainAfter.entitledToPlan()).as("expired exactly at period_end").isFalse();
        assertThat(Map.of("g", businessState(graceT), "p", businessState(plain), "h", businessState(held))).as("reads never write").isEqualTo(before);

        // restart: the jobs persist the same states, once
        clock.setOffset(Duration.ofMinutes(15));
        periodEndJob.enqueueDue();
        drain(runner(null, null), "period-end.persist", "scheduled-change.apply");
        assertThat(subRow(inGrace).get("status")).isEqualTo("PAST_DUE");
        assertThat(subRow(noGrace).get("status")).isEqualTo("EXPIRED");
        assertThat(changeRow(change).get("status")).as("the funded downgrade is applied by the first job").isEqualTo("APPLIED");
        Map<String, Object> once = Map.of("g", businessState(graceT), "p", businessState(plain), "h", businessState(held));
        periodEndJob.enqueueDue();
        drain(runner(null, null), "period-end.persist", "scheduled-change.apply");
        assertThat(Map.of("g", businessState(graceT), "p", businessState(plain), "h", businessState(held))).as("idempotent, one audit each").isEqualTo(once);
        evidence("consolidation.txt", "MT-05 jobs stopped | " + views + " | restart persisted: in-grace PAST_DUE, no-grace EXPIRED, funded downgrade APPLIED; second run unchanged");
    }

    private EffectiveView view(String subscriptionId, Instant now) {
        return evaluator.view(subscriptions.findById(subscriptionId).orElseThrow(), false, now);
    }

    // ---- R12: two runners on one mixed financial queue ----

    @Test
    void r12TwoRunnersOnAMixedFinancialQueueRunEveryClaimOnceWithOneEffectEach() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        List<Tenant> buyers = new ArrayList<>(), refunded = new ArrayList<>(), ending = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Tenant t = data.tenant("r12-buy-" + i);
            deliverSigned("evt_r12_" + i, paid(checkout(t, CheckoutKind.NEW, null), soon()));
            buyers.add(t);
        }
        for (int i = 0; i < 4; i++) {
            Tenant t = data.tenant("r12-refund-" + i);
            requestedRefund(t);
            refunded.add(t);
        }
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Tenant t = data.tenant("r12-end-" + i);
            rows.add(paidRow(t, Duration.ofSeconds(20)));
            ending.add(t);
        }
        clock.setOffset(Duration.ofMinutes(1));
        periodEndJob.enqueueDue();
        jdbc.update("update job set run_at = now() + interval '1 day' where status = 'PENDING' and type not in ('inbox.process', 'refund.execute', 'period-end.persist')");
        int jobs = count("select count(*) from job where status = 'PENDING' and run_at <= now()");

        ExecutorService pollers = Executors.newFixedThreadPool(2);
        try (HikariDataSource p1 = pool(); HikariDataSource p2 = pool()) {
            JobRunner r1 = runner(new JdbcTemplate(p1), null), r2 = runner(new JdbcTemplate(p2), null);
            int guard = 0;
            while (count("select count(*) from job where status in ('PENDING', 'RUNNING') and run_at <= now()") > 0 && guard++ < 200) {
                Future<List<Future<?>>> f1 = pollers.submit(r1::pollOnce), f2 = pollers.submit(r2::pollOnce);
                finish(join(f1));
                finish(join(f2));
            }
        } finally {
            pollers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select max(claims) from job where type in ('inbox.process', 'refund.execute', 'period-end.persist')", Integer.class)).as("no claim ran twice").isEqualTo(1);
        assertThat(count("select count(*) from job where type in ('inbox.process', 'refund.execute', 'period-end.persist') and status <> 'DONE'")).isZero();
        for (Tenant t : buyers) {
            assertThat(payments(t)).isEqualTo(1);
            assertThat(periods(t)).isEqualTo(1);
        }
        assertThat(fakeEt.refundsCreated()).as("one provider refund per request").isEqualTo(4);
        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(4);
        for (String sub : rows) {
            assertThat(subRow(sub).get("status")).isEqualTo("EXPIRED");
            assertThat(count("select count(*) from admin_audit_event where target_id = ? and action <> 'SUBSCRIPTION_PAID_ACTIVATED'", sub)).isEqualTo(1);
        }
        Map<String, Object> visible = assertLedgerAccounted("R12");
        evidence("races.txt", "R12 two runners, separate pools, mixed queue | due jobs=" + jobs + " (6 inbox, 4 refund, 4 period-end) | max claims=1 | effects: 6 activations, 4 provider refunds, 4 expiries with one audit each | visible=" + visible);
        assertThat(ending).hasSize(4);
        assertThat(refunded).hasSize(4);
    }
}
