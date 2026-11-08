package com.agafari.com.jobs;

import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.support.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** AGF SUB-R2-08 (MT-03): enqueue, claim, lease, fencing, limits, pause and retention on real PostgreSQL. */
class JobRunnerIntegrationTest extends AbstractIntegrationTest {

    public record Pay(String id, int n) {
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobQueue queue;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MonetizationLocks locks;
    @Autowired private DataSource dataSource;
    @Autowired private ApplicationContext context;

    private final List<JobRunner> runners = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @AfterEach
    void closeRunners() {
        runners.forEach(JobRunner::shutdown);
    }

    private static JobHandler<Pay> handler(String type, String provider, Duration cap, Consumer<Pay> body) {
        return new JobHandler<>() {
            @Override public String type() { return type; }
            @Override public Class<Pay> payloadType() { return Pay.class; }
            @Override public void handle(Pay p) { body.accept(p); }
            @Override public String provider() { return provider; }
            @Override public Duration transientCap() { return cap; }
        };
    }

    private JobRunner runner(JdbcTemplate template, JobHandler<?>... handlers) {
        JobRunner r = new JobRunner(template, List.of(handlers), mapper, 4, clock);
        runners.add(r);
        return r;
    }

    private JobRunner runner(JobHandler<?>... handlers) {
        return runner(jdbc, handlers);
    }

    private void enqueue(String type, String key, Pay p) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> queue.enqueue(type, key, p));
    }

    private static int drain(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // a handler that killed its worker
            }
        }
        return futures.size();
    }

    private Map<String, Object> row(String type, String key) {
        return jdbc.queryForMap("select status, claims, claim_token, lease_until, finished_at, last_error, last_error_kind, first_attempt_at from job where type = ? and key = ?", type, key);
    }

    private void due() {
        jdbc.update("update job set run_at = now() where status = 'PENDING'");
    }

    private void expireLeases() {
        jdbc.update("update job set lease_until = now() - interval '1 second' where status = 'RUNNING'");
    }

    // ---- enqueue

    @Test
    void enqueueStoresThePayloadAndItsCanonicalHash() {
        enqueue("t.a", "k", new Pay("x", 1));
        Map<String, Object> r = jdbc.queryForMap("select status, payload::text as payload, payload_hash, created_at is not null as created from job where key = 'k'");
        assertThat(r).containsEntry("status", "PENDING").containsEntry("created", true);
        assertThat(mapper.readTree((String) r.get("payload"))).isEqualTo(mapper.valueToTree(new Pay("x", 1)));
        assertThat(r.get("payload_hash")).isEqualTo(PayloadHash.of(mapper.valueToTree(new Pay("x", 1))));
    }

    @Test
    void theSameKeyAndPayloadIsANoOp() {
        enqueue("t.a", "k", new Pay("x", 1));
        enqueue("t.a", "k", new Pay("x", 1));
        assertThat(jdbc.queryForObject("select count(*) from job where key = 'k'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadThrows() {
        enqueue("t.a", "k", new Pay("x", 1));
        assertThatThrownBy(() -> enqueue("t.a", "k", new Pay("x", 2))).isInstanceOf(JobKeyConflict.class);
        assertThat(jdbc.queryForObject("select payload->>'n' from job where key = 'k'", String.class)).isEqualTo("1");
    }

    @Test
    void enqueueRollsBackWithTheCaller() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            queue.enqueue("t.a", "gone", new Pay("x", 1));
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from job where key = 'gone'", Integer.class)).isZero();
    }

    @Test
    void enqueueRefusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> queue.enqueue("t.a", "k", new Pay("x", 1))).isInstanceOf(IllegalStateException.class);
    }

    // ---- running

    @Test
    void aJobRunsOutsideATransactionWithItsPayloadAndFinishes() throws Exception {
        AtomicReference<Boolean> inTx = new AtomicReference<>();
        AtomicReference<Pay> seen = new AtomicReference<>();
        JobRunner r = runner(handler("t.ok", null, null, p -> { inTx.set(TransactionSynchronizationManager.isActualTransactionActive()); seen.set(p); }));
        enqueue("t.ok", "k", new Pay("x", 7));
        assertThat(drain(r.pollOnce())).isEqualTo(1);
        assertThat(inTx.get()).isFalse();
        assertThat(seen.get()).isEqualTo(new Pay("x", 7));
        Map<String, Object> job = row("t.ok", "k");
        assertThat(job).containsEntry("status", "DONE").containsEntry("claims", 1);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(job.get("first_attempt_at")).isNotNull();
    }

    @Test
    void aJobIsNotRunBeforeItsTimeOrWithoutAHandler() throws Exception {
        JobRunner r = runner(handler("t.ok", null, null, p -> { }));
        enqueue("t.ok", "later", new Pay("x", 1));
        jdbc.update("update job set run_at = now() + interval '1 hour' where key = 'later'");
        enqueue("t.other", "nobody", new Pay("x", 1));
        assertThat(drain(r.pollOnce())).isZero();
        assertThat(row("t.other", "nobody")).containsEntry("claims", 0);
    }

    // ---- AC1: claim-time counting

    @Test
    void aHandlerThatKillsItsWorkerEightTimesEndsDead() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(handler("t.poison", null, null, p -> { runs.incrementAndGet(); throw new Error("worker killed"); }));
        enqueue("t.poison", "k", new Pay("x", 1));
        for (int i = 0; i < 8; i++) {
            assertThat(drain(r.pollOnce())).as("run " + i).isEqualTo(1);
            assertThat(row("t.poison", "k")).containsEntry("status", "RUNNING").containsEntry("claims", i + 1);
            expireLeases();
        }
        assertThat(drain(r.pollOnce())).isZero();
        Map<String, Object> job = row("t.poison", "k");
        assertThat(job).containsEntry("status", "DEAD").containsEntry("claims", 8);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(runs.get()).isEqualTo(8);
        assertThat(drain(r.pollOnce())).isZero();
    }

    // ---- AC2: fencing and lease recovery

    @Test
    void aStaleWorkersCompletionChangesNothing() throws Exception {
        CountDownLatch w1Running = new CountDownLatch(1);
        CountDownLatch releaseW1 = new CountDownLatch(1);
        Set<String> effects = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        JobRunner w1 = runner(handler("t.fence", null, null, p -> {
            calls.incrementAndGet();
            w1Running.countDown();
            await(releaseW1);
            throw new PermanentJobException("the stale worker fails late", null);
        }));
        JobRunner w2 = runner(handler("t.fence", null, null, p -> { calls.incrementAndGet(); effects.add(p.id()); }));
        enqueue("t.fence", "k", new Pay("effect", 1));
        List<Future<?>> stale = w1.pollOnce();
        assertThat(w1Running.await(10, TimeUnit.SECONDS)).isTrue();
        expireLeases();
        assertThat(drain(w2.pollOnce())).isEqualTo(1);
        assertThat(row("t.fence", "k")).containsEntry("status", "DONE").containsEntry("claims", 2);
        releaseW1.countDown();
        drain(stale);
        Map<String, Object> job = row("t.fence", "k");
        assertThat(job).containsEntry("status", "DONE").containsEntry("last_error", null);
        assertThat(effects).containsExactly("effect");
        assertThat(calls.get()).isEqualTo(2);
    }

    /** W1 loses its lease while W2 is still working; W1's late result must not touch W2's claim. */
    private void staleResultWhileTheNewOwnerRuns(Runnable lateResult, String type) throws Exception {
        CountDownLatch w1Running = new CountDownLatch(1);
        CountDownLatch releaseW1 = new CountDownLatch(1);
        CountDownLatch w2Running = new CountDownLatch(1);
        CountDownLatch releaseW2 = new CountDownLatch(1);
        JobRunner w1 = runner(handler(type, null, null, p -> { w1Running.countDown(); await(releaseW1); lateResult.run(); }));
        JobRunner w2 = runner(handler(type, null, null, p -> { w2Running.countDown(); await(releaseW2); }));
        enqueue(type, "k", new Pay("x", 1));
        List<Future<?>> stale = w1.pollOnce();
        assertThat(w1Running.await(10, TimeUnit.SECONDS)).isTrue();
        expireLeases();
        List<Future<?>> owner = w2.pollOnce();
        assertThat(owner).hasSize(1);
        assertThat(w2Running.await(10, TimeUnit.SECONDS)).isTrue();
        Object token = row(type, "k").get("claim_token");
        releaseW1.countDown();
        drain(stale);
        assertThat(row(type, "k")).containsEntry("status", "RUNNING").containsEntry("claim_token", token).containsEntry("finished_at", null).containsEntry("last_error_kind", null);
        releaseW2.countDown();
        drain(owner);
        assertThat(row(type, "k")).containsEntry("status", "DONE");
    }

    @Test
    void aStaleSuccessDoesNotFinishTheNewOwnersClaim() throws Exception {
        staleResultWhileTheNewOwnerRuns(() -> { }, "t.stale-ok");
    }

    @Test
    void aStaleFailureDoesNotRescheduleTheNewOwnersClaim() throws Exception {
        staleResultWhileTheNewOwnerRuns(() -> { throw new TransientJobException("late", null); }, "t.stale-fail");
    }

    @Test
    void aClaimSkipsARowLockedByAnotherWorkerInsteadOfWaiting() throws Exception {
        Set<String> ran = ConcurrentHashMap.newKeySet();
        JobRunner r = runner(handler("t.skip", null, null, p -> ran.add(p.id())));
        enqueue("t.skip", "locked", new Pay("locked", 1));
        enqueue("t.skip", "free", new Pay("free", 2));
        ExecutorService poller = Executors.newSingleThreadExecutor();
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("select id from job where key = 'locked' for update"); var rs = lock.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Future<List<Future<?>>> poll = poller.submit(r::pollOnce);
                drain(poll.get(5, TimeUnit.SECONDS));
                assertThat(ran).containsExactly("free");
            }
            holder.rollback();
        } finally {
            poller.shutdownNow();
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRunningJobWithALiveLeaseIsNotRecovered() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobRunner r = runner(handler("t.live", null, null, p -> await(release)));
        enqueue("t.live", "k", new Pay("x", 1));
        List<Future<?>> running = r.pollOnce();
        JobRunner other = runner(handler("t.live", null, null, p -> { }));
        assertThat(drain(other.pollOnce())).isZero();
        assertThat(row("t.live", "k")).containsEntry("status", "RUNNING");
        release.countDown();
        drain(running);
        assertThat(row("t.live", "k")).containsEntry("status", "DONE");
    }

    // ---- AC4: two runners, separate pools

    @Test
    void twoRunnersWithSeparatePoolsNeverRunOneClaimTwice() throws Exception {
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        Consumer<Pay> body = p -> runs.computeIfAbsent(p.id(), k -> new AtomicInteger()).incrementAndGet();
        for (int i = 0; i < 60; i++) enqueue("t.two", "k" + i, new Pay("k" + i, i));
        try (HikariDataSource a = pool(); HikariDataSource b = pool()) {
            JobRunner r1 = runner(new JdbcTemplate(a), handler("t.two", null, null, body));
            JobRunner r2 = runner(new JdbcTemplate(b), handler("t.two", null, null, body));
            ExecutorService pollers = Executors.newFixedThreadPool(2);
            try {
                int guard = 0;
                while (jdbc.queryForObject("select count(*) from job where status <> 'DONE'", Integer.class) > 0 && guard++ < 200) {
                    Future<List<Future<?>>> f1 = pollers.submit(r1::pollOnce);
                    Future<List<Future<?>>> f2 = pollers.submit(r2::pollOnce);
                    drain(f1.get(30, TimeUnit.SECONDS));
                    drain(f2.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pollers.shutdownNow();
            }
        }
        assertThat(runs).hasSize(60);
        assertThat(runs.values()).allMatch(c -> c.get() == 1);
        assertThat(jdbc.queryForObject("select max(claims) from job", Integer.class)).isEqualTo(1);
    }

    private HikariDataSource pool() throws Exception {
        HikariDataSource p = new HikariDataSource();
        try (var c = dataSource.getConnection()) {
            p.setJdbcUrl(c.getMetaData().getURL());
        }
        p.setUsername(System.getenv().getOrDefault("AGAFARI_TEST_DB_USERNAME", "postgres"));
        p.setPassword(System.getenv().getOrDefault("AGAFARI_TEST_DB_PASSWORD", "postgres"));
        p.setMaximumPoolSize(2);
        return p;
    }

    // ---- AC6: errors

    @Test
    void aPermanentFailureBacksOffAndEndsDeadOnTheEighthClaim() throws Exception {
        JobRunner r = runner(handler("t.perm", null, null, p -> { throw new PermanentJobException("no", null); }));
        enqueue("t.perm", "k", new Pay("x", 1));
        for (int i = 1; i <= 7; i++) {
            drain(r.pollOnce());
            assertThat(row("t.perm", "k")).containsEntry("status", "PENDING").containsEntry("claims", i).containsEntry("last_error_kind", "PERMANENT").containsEntry("claim_token", null);
            due();
        }
        drain(r.pollOnce());
        assertThat(row("t.perm", "k")).containsEntry("status", "DEAD").containsEntry("claims", 8);
    }

    @Test
    void aFailureSchedulesTheBackoffFromDatabaseTime() throws Exception {
        JobRunner r = runner(handler("t.back", null, null, p -> { throw new TransientJobException("later", null); }));
        enqueue("t.back", "k", new Pay("x", 1));
        drain(r.pollOnce());
        Double seconds = jdbc.queryForObject("select extract(epoch from run_at - now()) from job where key = 'k'", Double.class);
        assertThat(seconds).isBetween(50.0, 61.0);
        assertThat(drain(r.pollOnce())).isZero();
    }

    @Test
    void transientFailuresOutliveTheClaimLimitUntilTheCap() throws Exception {
        JobRunner r = runner(handler("refund.test", null, null, p -> { throw new TransientJobException("timeout", null); }));
        enqueue("refund.test", "k", new Pay("x", 1));
        for (int i = 1; i <= 12; i++) {
            drain(r.pollOnce());
            assertThat(row("refund.test", "k")).as("claim " + i).containsEntry("status", "PENDING").containsEntry("last_error_kind", "TRANSIENT");
            due();
        }
        jdbc.update("update job set first_attempt_at = now() - interval '72 hours 1 second' where key = 'k'");
        drain(r.pollOnce());
        assertThat(row("refund.test", "k")).containsEntry("status", "DEAD").containsEntry("claims", 13);
    }

    @Test
    void aNonFinancialJobWithoutADeclaredCapIsNotTimeLimited() throws Exception {
        JobRunner r = runner(handler("t.nocap", null, null, p -> { throw new TransientJobException("timeout", null); }));
        enqueue("t.nocap", "k", new Pay("x", 1));
        drain(r.pollOnce());
        jdbc.update("update job set first_attempt_at = now() - interval '400 days', run_at = now() where key = 'k'");
        drain(r.pollOnce());
        assertThat(row("t.nocap", "k")).containsEntry("status", "PENDING");
    }

    @Test
    void aHandlerDeclaredCapApplies() throws Exception {
        JobRunner r = runner(handler("t.cap", null, Duration.ofHours(1), p -> { throw new TransientJobException("timeout", null); }));
        enqueue("t.cap", "k", new Pay("x", 1));
        drain(r.pollOnce());
        jdbc.update("update job set first_attempt_at = now() - interval '2 hours', run_at = now() where key = 'k'");
        drain(r.pollOnce());
        assertThat(row("t.cap", "k")).containsEntry("status", "DEAD");
    }

    @Test
    void anUnreadablePayloadIsAPermanentFailureNotACrash() throws Exception {
        JobRunner r = runner(handler("t.bad", null, null, p -> { }));
        jdbc.update("insert into job (id, type, key, payload, payload_hash) values (?, 't.bad', 'k', '{\"n\":\"not a number\"}'::jsonb, 'h')", UUID.randomUUID().toString());
        drain(r.pollOnce());
        assertThat(row("t.bad", "k")).containsEntry("status", "PENDING").containsEntry("last_error_kind", "PERMANENT");
    }

    @Test
    void theStoredErrorIsBounded() throws Exception {
        JobRunner r = runner(handler("t.long", null, null, p -> { throw new PermanentJobException("x".repeat(5000), null); }));
        enqueue("t.long", "k", new Pay("x", 1));
        drain(r.pollOnce());
        assertThat(((String) row("t.long", "k").get("last_error")).length()).isLessThanOrEqualTo(1000);
    }

    // ---- per-provider pause

    @Test
    void tenConsecutiveTransientFailuresPauseThatProviderForFiveMinutes() throws Exception {
        JobHandler<Pay> failing = handler("t.stripe", "STRIPE", null, p -> { throw new TransientJobException("503", null); });
        AtomicInteger other = new AtomicInteger();
        JobRunner r = runner(failing, handler("t.chapa", "CHAPA", null, p -> other.incrementAndGet()));
        enqueue("t.stripe", "k", new Pay("x", 1));
        for (int i = 1; i <= 9; i++) {
            drain(r.pollOnce());
            due();
            assertThat(r.isPaused("STRIPE")).as("after " + i).isFalse();
        }
        drain(r.pollOnce());
        due();
        assertThat(r.isPaused("STRIPE")).isTrue();
        enqueue("t.chapa", "c", new Pay("x", 1));
        assertThat(drain(r.pollOnce())).as("only the other provider runs").isEqualTo(1);
        assertThat(other.get()).isEqualTo(1);
        assertThat(row("t.stripe", "k")).containsEntry("claims", 10);
        now.set(now.get().plus(Duration.ofMinutes(5)).minusNanos(1));
        assertThat(r.isPaused("STRIPE")).isTrue();
        now.set(now.get().plusNanos(1));
        assertThat(r.isPaused("STRIPE")).isFalse();
        assertThat(drain(r.pollOnce())).isEqualTo(1);
    }

    @Test
    void aSuccessResetsTheProvidersCount() throws Exception {
        AtomicInteger n = new AtomicInteger();
        JobRunner r = runner(handler("t.flaky", "STRIPE", null, p -> { if (n.incrementAndGet() != 9) throw new TransientJobException("503", null); }));
        for (int i = 0; i < 12; i++) {
            enqueue("t.flaky", "k" + i, new Pay("x", 1));
            drain(r.pollOnce());
        }
        assertThat(r.isPaused("STRIPE")).isFalse();
    }

    // ---- retention

    private void finished(String type, String key, String status, String age) {
        jdbc.update("insert into job (id, type, key, payload, payload_hash, status, finished_at) values (?, ?, ?, '{}'::jsonb, 'h', ?, now() - cast(? as interval))", UUID.randomUUID().toString(), type, key, status, age);
    }

    @Test
    void retentionRemovesOnlyOldNonFinancialDoneJobs() {
        finished("notification.send", "old", "DONE", "31 days");
        finished("notification.send", "recent", "DONE", "29 days");
        finished("notification.send", "dead", "DEAD", "400 days");
        finished("refund.execute", "r", "DONE", "400 days");
        finished("checkout.create", "c", "DONE", "400 days");
        finished("inbox.process", "i", "DONE", "400 days");
        assertThat(runner().prune()).isEqualTo(1);
        assertThat(jdbc.queryForList("select key from job order by key", String.class)).containsExactly("c", "dead", "i", "r", "recent");
    }

    // ---- locks, guard, flag

    private boolean tryInTransaction(long key) {
        Boolean got = new TransactionTemplate(transactions).execute(s -> locks.tryJob(key));
        return Boolean.TRUE.equals(got);
    }

    @Test
    void tryJobLetsOneTransactionInAtATime() throws Exception {
        long key = 4_400_000_777L;
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = other.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                boolean got = locks.tryJob(key);
                held.countDown();
                await(done);
                return got;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(tryInTransaction(key)).isFalse();
            done.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(tryInTransaction(key)).isTrue();
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void aProviderCallInsideATransactionIsRefused() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> ProviderCallGuard.assertNoTransaction()))
                .isInstanceOf(ProviderCallInTransactionException.class);
        assertThatCode(ProviderCallGuard::assertNoTransaction).doesNotThrowAnyException();
    }

    @Test
    void theFlagIsOffByDefaultSoNothingPolls() {
        assertThat(context.getBeanNamesForType(JobPoller.class)).isEmpty();
    }

    @Test
    void thePollerExistsOnlyWithTheFlagOn() {
        ApplicationContextRunner contexts = new ApplicationContextRunner().withBean(JobRunner.class, () -> mock(JobRunner.class)).withBean(MonetizationLocks.class, () -> mock(MonetizationLocks.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class)).withUserConfiguration(JobPoller.class);
        contexts.run(c -> assertThat(c).doesNotHaveBean(JobPoller.class));
        contexts.withPropertyValues("app.jobs.enabled=false").run(c -> assertThat(c).doesNotHaveBean(JobPoller.class));
        contexts.withPropertyValues("app.jobs.enabled=true").run(c -> assertThat(c).hasSingleBean(JobPoller.class));
    }
}
