package com.agafari.com.subscription;

import com.agafari.com.billing.TestEvidence;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF PROVIDER grace-end correction, against PostgreSQL. The rows are real PROVIDER rows with test-only payment evidence, so
 * the V23.2 guards stay active. Amounts and the seven-day grace are fixtures, not prices or a decision (AD-10 and AD-7 are open).
 */
class ProviderGraceEndJobTest extends AbstractIntegrationTest {

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2030-02-01T00:00:00Z");
    private static final Instant GRACE_END = END.plus(Duration.ofDays(7));
    private static final Instant NEXT = Instant.parse("2030-03-01T00:00:00Z");
    private static final String ENDED_AUDIT = "SUBSCRIPTION_PERIOD_ENDED";
    private static final String PAST_DUE_AUDIT = "SUBSCRIPTION_PAST_DUE";

    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private MonetizationLocks locks;
    @Autowired private MonetizationClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PeriodEndJob job;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private ObjectMapper mapper;

    private final List<JobRunner> runners = new ArrayList<>();
    private User admin;
    private Tenant owner;
    private String price;

    @BeforeEach
    void setUp() {
        clock.reset();
        admin = data.admin("grace-admin");
        owner = data.tenant("grace-owner");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        price = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, active, valid_from) values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, true, now())", price);
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, 'ET', 'P1')", UUID.randomUUID().toString(), owner.businessId());
        evaluator.setPeriodEndEnabled(true);
    }

    @AfterEach
    void reset() {
        runners.forEach(JobRunner::shutdown);
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    private record Ev(String payment, String invoice) {
    }

    private Ev evidence(Instant start, Instant end) {
        String invoice = UUID.randomUUID().toString(), payment = UUID.randomUUID().toString();
        jdbc.update("insert into billing_invoice (id, business_id, price_id, period_start, period_end, amount_minor, currency, status, provider_invoice_ref) values (?, ?, ?, ?, ?, 1000, 'ETB', 'PAID', ?)",
                invoice, owner.businessId(), price, Timestamp.from(start), Timestamp.from(end), UUID.randomUUID().toString());
        jdbc.update("insert into payment (id, business_id, invoice_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                + "values (?, ?, ?, 'P1', ?, 1000, 'ETB', 'CHARGE', 'SUCCEEDED', now(), now(), 'SIGNED_WEBHOOK+API_FETCH')", payment, owner.businessId(), invoice, UUID.randomUUID().toString());
        return new Ev(payment, invoice);
    }

    private <T> T locked(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            return work.get();
        });
    }

    private String activate(Integer graceDays) {
        Ev e = evidence(START, END);
        return locked(() -> lifecycle.activatePaid(owner.businessId(), new PaidActivation("MODEL_4", 1, price, "P1", null, graceDays, START, END, TestEvidence.ref(e.payment(), e.invoice()), Instant.now()))).getId();
    }

    private void renew(String id) {
        Ev e = evidence(END, NEXT);
        locked(() -> lifecycle.renew(id, new PeriodExtension(END, NEXT, TestEvidence.ref(e.payment(), e.invoice()), Instant.now())));
    }

    private void at(Instant target) {
        clock.setOffset(Duration.between(Instant.now(), target));
    }

    private void inGrace() {
        at(END.plus(Duration.ofDays(1)));
    }

    private void afterGrace() {
        at(GRACE_END.plus(Duration.ofDays(1)));
    }

    private JobRunner runner(JobHandler<?> handler) {
        JobRunner r = new JobRunner(jdbc, List.of(handler), mapper, 4, Clock.systemUTC());
        runners.add(r);
        return r;
    }

    private JobHandler<PeriodEndJob.Payload> wrapped(Consumer<PeriodEndJob.Payload> before, Consumer<PeriodEndJob.Payload> after) {
        return new JobHandler<>() {
            @Override public String type() { return PeriodEndJob.TYPE; }
            @Override public Class<PeriodEndJob.Payload> payloadType() { return PeriodEndJob.Payload.class; }
            @Override public void handle(PeriodEndJob.Payload p) {
                before.accept(p);
                job.handle(p);
                after.accept(p);
            }
        };
    }

    private static void drain(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // a killed worker
            }
        }
    }

    private void run() throws Exception {
        drain(runner(job).pollOnce());
    }

    private PeriodEndJob.Payload payload(String id, Instant end) {
        return new PeriodEndJob.Payload(owner.businessId(), id, end);
    }

    private Map<String, Object> stored(String id) {
        return jdbc.queryForMap("select status, ended_at, ended_reason, past_due_since, row_version from business_subscription where id = ?", id);
    }

    private String status(String id) {
        return (String) stored(id).get("status");
    }

    private int version(String id) {
        return (Integer) stored(id).get("row_version");
    }

    private int audits(String action, String id) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ? and target_id = ?", Integer.class, action, id);
    }

    private List<String> keys() {
        return jdbc.queryForList("select key from job where type = ? order by created_at, key", String.class, PeriodEndJob.TYPE);
    }

    private int doneJobs() {
        return jdbc.queryForObject("select count(*) from job where type = ? and status = 'DONE'", Integer.class, PeriodEndJob.TYPE);
    }

    private void expireLeases() {
        jdbc.update("update job set lease_until = now() - interval '1 second' where status = 'RUNNING'");
    }

    private void assertExpiredAt(String id, Instant graceEnd) {
        Map<String, Object> row = stored(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED");
        assertThat(((Timestamp) row.get("ended_at")).toInstant()).isEqualTo(graceEnd);
        assertThat(audits(ENDED_AUDIT, id)).isEqualTo(1);
    }

    private String key(String id, String target) {
        return id + "@" + END + "#" + target;
    }

    private void concurrently(PeriodEndJob.Payload p) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(6);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    job.handle(p);
                    return null;
                }));
            }
            for (Future<?> f : all) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- PAST_DUE, then EXPIRED

    @Test
    void aCompletedPastDueJobDoesNotSuppressExpiry() throws Exception {
        String id = activate(7);
        inGrace();
        assertThat(job.enqueueDue()).isEqualTo(1);
        run();
        assertThat(status(id)).isEqualTo("PAST_DUE");
        assertThat(((Timestamp) stored(id).get("past_due_since")).toInstant()).isEqualTo(END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
        assertThat(doneJobs()).isEqualTo(1);

        afterGrace();
        assertThat(job.enqueueDue()).isEqualTo(1);
        run();
        assertExpiredAt(id, GRACE_END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
        assertThat(keys()).containsExactlyInAnyOrder(key(id, "PAST_DUE"), key(id, "EXPIRED"));
        assertThat(doneJobs()).isEqualTo(2);
    }

    @Test
    void anOutageAcrossTheGraceWritesOnceWithOneExpiredJob() throws Exception {
        String id = activate(7);
        afterGrace();
        job.enqueueDue();
        run();
        assertExpiredAt(id, GRACE_END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isZero();
        assertThat(keys()).containsExactly(key(id, "EXPIRED"));
    }

    @Test
    void noRecordedGraceExpiresAtThePeriodEnd() throws Exception {
        String id = activate(null);
        inGrace();
        job.enqueueDue();
        run();
        assertExpiredAt(id, END);
        assertThat(keys()).containsExactly(key(id, "EXPIRED"));
    }

    @Test
    void theRowsPersistedGraceIsUsed() throws Exception {
        String id = activate(3);
        at(END.plus(Duration.ofDays(4)));
        job.enqueueDue();
        run();
        assertExpiredAt(id, END.plus(Duration.ofDays(3)));
    }

    // ---- idempotency of each stage

    @Test
    void eachStageIsEnqueuedOnce() throws Exception {
        String id = activate(7);
        inGrace();
        job.enqueueDue();
        job.enqueueDue();
        assertThat(keys()).containsExactly(key(id, "PAST_DUE"));
        run();
        assertThat(job.enqueueDue()).isZero();
        afterGrace();
        job.enqueueDue();
        job.enqueueDue();
        assertThat(keys()).containsExactlyInAnyOrder(key(id, "PAST_DUE"), key(id, "EXPIRED"));
        run();
        assertThat(job.enqueueDue()).isZero();
        assertThat(keys()).hasSize(2);
    }

    @Test
    void repeatingEachStageChangesNothing() throws Exception {
        String id = activate(7);
        inGrace();
        job.handle(payload(id, END));
        int afterFirst = version(id);
        job.handle(payload(id, END));
        assertThat(version(id)).isEqualTo(afterFirst);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);

        afterGrace();
        job.handle(payload(id, END));
        int afterSecond = version(id);
        job.handle(payload(id, END));
        assertThat(version(id)).isEqualTo(afterSecond);
        assertExpiredAt(id, GRACE_END);
    }

    @Test
    void concurrentHandlersWriteEachStageOnce() throws Exception {
        String id = activate(7);
        inGrace();
        concurrently(payload(id, END));
        assertThat(status(id)).isEqualTo("PAST_DUE");
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
        afterGrace();
        concurrently(payload(id, END));
        assertExpiredAt(id, GRACE_END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
    }

    @Test
    void twoRunnersWriteEachStageOnce() throws Exception {
        String id = activate(7);
        JobRunner r1 = runner(job), r2 = runner(job);
        inGrace();
        job.enqueueDue();
        drain(r1.pollOnce());
        drain(r2.pollOnce());
        afterGrace();
        job.enqueueDue();
        List<Future<?>> first = r1.pollOnce(), second = r2.pollOnce();
        drain(first);
        drain(second);
        assertExpiredAt(id, GRACE_END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
        assertThat(doneJobs()).isEqualTo(2);
    }

    @Test
    void aWorkerKilledBeforeEachWriteIsRecovered() throws Exception {
        String id = activate(7);
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(wrapped(p -> { if (runs.incrementAndGet() % 2 == 1) throw new Error("killed before the write"); }, p -> { }));
        inGrace();
        job.enqueueDue();
        drain(r.pollOnce());
        assertThat(status(id)).isEqualTo("ACTIVE");
        expireLeases();
        drain(r.pollOnce());
        assertThat(status(id)).isEqualTo("PAST_DUE");
        afterGrace();
        job.enqueueDue();
        drain(r.pollOnce());
        assertThat(status(id)).isEqualTo("PAST_DUE");
        expireLeases();
        drain(r.pollOnce());
        assertExpiredAt(id, GRACE_END);
    }

    @Test
    void aWorkerKilledAfterEachCommitDoesNotRepeatIt() throws Exception {
        String id = activate(7);
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(wrapped(p -> { }, p -> { if (runs.incrementAndGet() % 2 == 1) throw new Error("killed after the commit"); }));
        inGrace();
        job.enqueueDue();
        drain(r.pollOnce());
        expireLeases();
        drain(r.pollOnce());
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
        afterGrace();
        job.enqueueDue();
        drain(r.pollOnce());
        expireLeases();
        drain(r.pollOnce());
        assertExpiredAt(id, GRACE_END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1);
    }

    // ---- early and stale jobs re-evaluate

    @Test
    void anEarlyJobWritesNothing() {
        String id = activate(7);
        at(END.minus(Duration.ofDays(1)));
        job.handle(payload(id, END));
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(version(id)).isZero();
    }

    @Test
    void anEarlyExpiredJobDuringGraceDoesNotExpire() {
        String id = activate(7);
        inGrace();
        job.handle(payload(id, END));
        int v = version(id);
        job.handle(payload(id, END));
        assertThat(status(id)).isEqualTo("PAST_DUE");
        assertThat(version(id)).isEqualTo(v);
        assertThat(audits(ENDED_AUDIT, id)).isZero();
    }

    @Test
    void aPastDueJobDelayedBeyondTheGraceWritesExpiredOnce() throws Exception {
        String id = activate(7);
        inGrace();
        job.enqueueDue();
        afterGrace();
        job.enqueueDue();
        assertThat(keys()).containsExactlyInAnyOrder(key(id, "PAST_DUE"), key(id, "EXPIRED"));
        run();
        assertExpiredAt(id, GRACE_END);
        assertThat(audits(PAST_DUE_AUDIT, id)).isZero();
        assertThat(doneJobs()).isEqualTo(2);
    }

    @Test
    void aStaleJobAfterExpiryWritesNothing() {
        String id = activate(7);
        afterGrace();
        job.handle(payload(id, END));
        int v = version(id);
        job.handle(payload(id, END));
        assertThat(version(id)).isEqualTo(v);
        assertExpiredAt(id, GRACE_END);
    }

    // ---- an extended period

    @Test
    void aRenewalBeforeTheJobRunsWins() throws Exception {
        String id = activate(7);
        inGrace();
        job.enqueueDue();
        renew(id);
        int v = version(id);
        run();
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(version(id)).isEqualTo(v);
        assertThat(audits(PAST_DUE_AUDIT, id)).isEqualTo(1); // from the renewal's own materialize; the job adds none
    }

    @Test
    void aRenewalBeforeThePeriodEndLeavesNothingDue() {
        String id = activate(7);
        at(END.minus(Duration.ofDays(1)));
        renew(id);
        inGrace();
        assertThat(job.enqueueDue()).isZero();
        assertThat(keys()).isEmpty();
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(audits(PAST_DUE_AUDIT, id)).isZero();
    }

    @Test
    void aRenewalDuringGraceBeatsAnExpiryJobEnqueuedEarlier() throws Exception {
        String id = activate(7);
        inGrace();
        job.enqueueDue();
        run();
        assertThat(status(id)).isEqualTo("PAST_DUE");
        renew(id);
        assertThat(status(id)).isEqualTo("ACTIVE");
        int v = version(id);
        afterGrace();
        assertThat(job.enqueueDue()).isZero();
        job.handle(payload(id, END)); // a stale job for the old period
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(version(id)).isEqualTo(v);
        assertThat(audits(ENDED_AUDIT, id)).isZero();
    }

    @Test
    void aRenewedPeriodGetsItsOwnKeysAndBothStagesRunAgain() throws Exception {
        String id = activate(7);
        inGrace();
        job.enqueueDue();
        run();
        renew(id);
        at(NEXT.plus(Duration.ofDays(1)));
        job.enqueueDue();
        run();
        assertThat(status(id)).isEqualTo("PAST_DUE");
        assertThat(keys()).contains(id + "@" + NEXT + "#PAST_DUE");
        at(NEXT.plus(Duration.ofDays(8)));
        job.enqueueDue();
        run();
        assertExpiredAt(id, NEXT.plus(Duration.ofDays(7)));
        assertThat(keys()).contains(id + "@" + NEXT + "#EXPIRED").hasSize(3);
    }

    // ---- MANUAL and OWNER are unchanged

    private String plainRow(Tenant t, String source, String plan, Instant end) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, open_ended, row_version) "
                + "values (?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, ?, ?, false, 0)", id, t.businessId(), plan, Timestamp.from(end), source, Timestamp.from(end.minus(Duration.ofDays(30))), admin.getId(), Timestamp.from(end.minus(Duration.ofDays(30))), admin.getId());
        return id;
    }

    @Test
    void manualAndOwnerKeysAndOutcomesAreUnchanged() throws Exception {
        Tenant second = data.tenant("grace-second");
        Instant end = Instant.parse("2020-01-01T00:00:00Z");
        String manual = plainRow(owner, "MANUAL", "MODEL_4", end), own = plainRow(second, "OWNER", "MODEL_2", end);
        assertThat(job.enqueueDue()).isEqualTo(2);
        assertThat(keys()).containsExactlyInAnyOrder(manual + "@2020-01-01T00:00:00Z", own + "@2020-01-01T00:00:00Z");
        run();
        for (String id : List.of(manual, own)) {
            assertThat(stored(id)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PERIOD_END").containsEntry("row_version", 1);
            assertThat(audits(ENDED_AUDIT, id)).isEqualTo(1);
            assertThat(audits(PAST_DUE_AUDIT, id)).isZero();
        }
        assertThat(job.enqueueDue()).isZero();
        assertThat(keys()).hasSize(2);
    }

    @Test
    void theRepeatedJobCannotBeRecreatedForAManualRow() throws Exception {
        Instant end = Instant.parse("2020-01-01T00:00:00Z");
        String manual = plainRow(owner, "MANUAL", "MODEL_4", end);
        job.enqueueDue();
        job.enqueueDue();
        assertThat(keys()).containsExactly(manual + "@2020-01-01T00:00:00Z");
    }

    @Test
    void theJobCreatesNoProviderRow() throws Exception {
        Instant end = Instant.parse("2020-01-01T00:00:00Z");
        plainRow(owner, "MANUAL", "MODEL_4", end);
        job.enqueueDue();
        run();
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where source = 'PROVIDER'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_period", Integer.class)).isZero();
    }

    @Test
    void theHandlerWritesNothingWithTheFlagOff() {
        String id = activate(7);
        afterGrace();
        evaluator.setPeriodEndEnabled(false);
        job.handle(payload(id, END));
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(job.enqueueDue()).isZero();
    }
}
