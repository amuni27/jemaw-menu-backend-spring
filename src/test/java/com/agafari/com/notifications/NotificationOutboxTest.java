package com.agafari.com.notifications;

import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R2-09: the notice outbox and {@code notification.send} on real PostgreSQL. The EMAIL sender here is a test fake (D5 is open). */
class NotificationOutboxTest extends AbstractIntegrationTest {

    @FunctionalInterface
    interface Behaviour {
        void on(int call) throws Exception;
    }

    static final class FakeSender implements NotificationSender {
        final NotificationChannel channel;
        final boolean retrySafe;
        volatile Behaviour behaviour = c -> { };
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        FakeSender(NotificationChannel channel, boolean retrySafe) {
            this.channel = channel;
            this.retrySafe = retrySafe;
        }

        @Override public NotificationChannel channel() { return channel; }
        @Override public boolean retrySafeAfterUncertainOutcome() { return retrySafe; }

        @Override public void deliver(NotificationMessage m) throws Exception {
            behaviour.on(calls.computeIfAbsent(m.id(), k -> new AtomicInteger()).incrementAndGet());
        }

        int callsFor(String id) {
            AtomicInteger n = calls.get(id);
            return n == null ? 0 : n.get();
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobQueue queue;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private NotificationOutbox springOutbox;

    private final FakeSender inApp = new FakeSender(NotificationChannel.IN_APP, true);
    private final FakeSender email = new FakeSender(NotificationChannel.EMAIL, false);
    private final FakeSender verifiedEmail = new FakeSender(NotificationChannel.EMAIL, true);
    private final List<JobRunner> runners = new ArrayList<>();
    private String business;
    private NotificationOutbox outbox;

    @BeforeEach
    void setUp() {
        business = data.tenant("notice").businessId();
        outbox = new NotificationOutbox(jdbc, queue, mapper, List.of(inApp, email));
    }

    @AfterEach
    void close() {
        runners.forEach(JobRunner::shutdown);
    }

    private JobRunner runner(NotificationSender... senders) {
        JobRunner r = new JobRunner(jdbc, List.of(new NotificationSendHandler(jdbc, List.of(senders))), mapper, 4, Clock.systemUTC());
        runners.add(r);
        return r;
    }

    private boolean add(NotificationOutbox o, NotificationKind kind, String key, NotificationChannel channel) {
        Boolean created = new TransactionTemplate(transactions).execute(s -> o.add(business, kind, key, channel, Map.of("a", 1)));
        return Boolean.TRUE.equals(created);
    }

    private String addAndId(NotificationChannel channel, NotificationKind kind, String key) {
        add(outbox, kind, key, channel);
        return id(kind, key, channel);
    }

    private String id(NotificationKind kind, String key, NotificationChannel channel) {
        return jdbc.queryForObject("select id from notification where business_id = ? and kind = ? and key = ? and channel = ?", String.class, business, kind.name(), key, channel.name());
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select status, attempts, channel, kind_class from notification where id = ?", id);
    }

    private Map<String, Object> job(String id) {
        return jdbc.queryForMap("select status, claims from job where type = 'notification.send' and key = ?", id);
    }

    private int jobs() {
        return jdbc.queryForObject("select count(*) from job where type = 'notification.send'", Integer.class);
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

    private void due() {
        jdbc.update("update job set run_at = now() where status = 'PENDING'");
    }

    private void expireLeases() {
        jdbc.update("update job set lease_until = now() - interval '1 second' where status = 'RUNNING'");
    }

    private void markRead(String id, boolean expected) {
        assertThat(outbox.markRead(business, id)).isEqualTo(expected);
    }

    // ---- creation

    @Test
    void aNewNoticeStartsPendingWithOneJobAndItsClass() {
        assertThat(add(outbox, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP)).isTrue();
        String id = id(NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP);
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("channel", "IN_APP").containsEntry("kind_class", "ESSENTIAL");
        assertThat(job(id)).containsEntry("status", "PENDING").containsEntry("claims", 0);
        assertThat(jdbc.queryForObject("select template_data->>'a' from notification where id = ?", String.class, id)).isEqualTo("1");
    }

    @Test
    void theInAppShortcutUsesTheInAppChannel() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> outbox.add(business, NotificationKind.EXPIRY_NOTICE, "k", Map.of()));
        assertThat(row(id(NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP))).containsEntry("channel", "IN_APP");
    }

    @Test
    void aNoticeInARolledBackTransactionNeverAppears() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            outbox.add(business, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP, Map.of());
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, business)).isZero();
        assertThat(jobs()).isZero();
    }

    @Test
    void addRefusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> outbox.add(business, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP, Map.of())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, business)).as("nothing committed without its job").isZero();
    }

    @Test
    void aRepeatedNoticeIsANoOpWithNoSecondRowOrJob() {
        assertThat(add(outbox, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP)).isTrue();
        assertThat(add(outbox, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, business)).isEqualTo(1);
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void concurrentRepeatsCreateOneRowAndOneJob() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return add(outbox, NotificationKind.EXPIRY_NOTICE, "race", NotificationChannel.IN_APP);
                }));
            }
            int created = 0;
            for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) created++;
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, business)).isEqualTo(1);
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void theApplicationHasNoEmailChannelBeforeD5() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s ->
                springOutbox.add(business, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.EMAIL, Map.of()))).isInstanceOf(NotificationChannelUnavailableException.class);
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, business)).isZero();
    }

    @Test
    void eachKindIsStoredWithItsClass() {
        assertThat(NotificationKind.RENEWAL_PAYMENT_REQUEST.kindClass()).isEqualTo(NotificationKindClass.PAYMENT_REQUEST);
        assertThat(NotificationKind.EXPIRY_NOTICE.kindClass()).isEqualTo(NotificationKindClass.ESSENTIAL);
        assertThat(NotificationKind.REVIEW_STATUS.kindClass()).isEqualTo(NotificationKindClass.ESSENTIAL);
        for (NotificationKind kind : NotificationKind.values()) {
            assertThat(row(addAndId(NotificationChannel.IN_APP, kind, "c"))).as(kind.name()).containsEntry("kind_class", kind.kindClass().name());
        }
    }

    @Test
    void channelsHaveIndependentRowsJobsAndState() throws Exception {
        String app = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "same");
        String mail = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "same");
        assertThat(app).isNotEqualTo(mail);
        assertThat(jobs()).isEqualTo(2);
        email.behaviour = c -> { throw new NotificationNotSentException("rejected", false); };
        drain(runner(inApp, email).pollOnce());
        assertThat(row(app)).containsEntry("status", "SENT");
        assertThat(row(mail)).containsEntry("status", "FAILED");
    }

    // ---- delivery

    @Test
    void anInAppNoticeIsDeliveredOnce() throws Exception {
        String id = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "k");
        JobRunner r = runner(inApp);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 1);
        assertThat(job(id)).containsEntry("status", "DONE");
        assertThat(inApp.callsFor(id)).isEqualTo(1);
    }

    @Test
    void aDeliveryFailureRetriesWithoutTouchingTheBusinessRow() throws Exception {
        jdbc.update("update business set name = 'Before' where id = ?", business);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("update business set name = 'Committed' where id = ?", business);
            outbox.add(business, NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP, Map.of());
        });
        String id = id(NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP);
        inApp.behaviour = c -> { if (c == 1) throw new NotificationNotSentException("db hiccup", true); };
        JobRunner r = runner(inApp);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        assertThat(job(id)).containsEntry("status", "PENDING").containsEntry("claims", 1);
        assertThat(jdbc.queryForObject("select name from business where id = ?", String.class, business)).isEqualTo("Committed");
        due();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT");
        assertThat(jdbc.queryForObject("select name from business where id = ?", String.class, business)).isEqualTo("Committed");
    }

    @Test
    void aDefiniteRetryableFailureOnEmailStaysRetryable() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        email.behaviour = c -> { if (c == 1) throw new NotificationNotSentException("connection refused", true); };
        JobRunner r = runner(email);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        due();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 1);
        assertThat(email.callsFor(id)).isEqualTo(2);
    }

    @Test
    void aRowEndsFailedWhenItsJobRunsOutOfClaims() throws Exception {
        String id = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "k");
        inApp.behaviour = c -> { throw new NotificationNotSentException("down", true); };
        JobRunner r = runner(inApp);
        for (int i = 1; i < JobFailurePolicy.MAX_CLAIMS; i++) {
            drain(r.pollOnce());
            assertThat(row(id)).as("claim " + i).containsEntry("status", "PENDING");
            due();
        }
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("claims", 8);
        assertThat(inApp.callsFor(id)).isEqualTo(8);
    }

    @Test
    void aRowEndsFailedWhenItsJobReachesTheTimeCap() throws Exception {
        String id = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "k");
        inApp.behaviour = c -> { throw new NotificationNotSentException("down", true); };
        JobRunner r = runner(inApp);
        drain(r.pollOnce());
        jdbc.update("update job set first_attempt_at = now() - interval '24 hours 1 second', run_at = now() where key = ?", id);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("claims", 2);
    }

    @Test
    void aDefinitePermanentFailureEndsFailedWithoutRetry() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        email.behaviour = c -> { throw new NotificationNotSentException("bad address", false); };
        JobRunner r = runner(email);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(job(id)).containsEntry("status", "DONE");
        due();
        drain(r.pollOnce());
        assertThat(email.callsFor(id)).isEqualTo(1);
    }

    // ---- uncertain outcomes

    @Test
    void anUncertainEmailIsNotRetried() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        email.behaviour = c -> { throw new java.net.SocketTimeoutException("no answer"); };
        JobRunner r = runner(email);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempts", 1);
        assertThat(job(id)).containsEntry("status", "DONE");
        due();
        drain(r.pollOnce());
        assertThat(email.callsFor(id)).isEqualTo(1);
    }

    @Test
    void aVerifiedRetrySafeSenderMayRetryAnUncertainEssentialNotice() throws Exception {
        outbox = new NotificationOutbox(jdbc, queue, mapper, List.of(verifiedEmail));
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        verifiedEmail.behaviour = c -> { if (c == 1) throw new java.net.SocketTimeoutException("no answer"); };
        JobRunner r = runner(verifiedEmail);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        due();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT");
        assertThat(verifiedEmail.callsFor(id)).isEqualTo(2);
    }

    @Test
    void anExternalPaymentRequestIsNeverRetriedOnAnUncertainOutcomeEvenIfTheProviderIsRetrySafe() throws Exception {
        outbox = new NotificationOutbox(jdbc, queue, mapper, List.of(verifiedEmail));
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.RENEWAL_PAYMENT_REQUEST, "k");
        verifiedEmail.behaviour = c -> { throw new java.net.SocketTimeoutException("no answer"); };
        JobRunner r = runner(verifiedEmail);
        drain(r.pollOnce());
        due();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("kind_class", "PAYMENT_REQUEST");
        assertThat(verifiedEmail.callsFor(id)).isEqualTo(1);
    }

    @Test
    void aWorkerKilledAfterAnEmailAttemptDoesNotSendAgain() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        email.behaviour = c -> { throw new Error("worker killed after the send left"); };
        JobRunner r = runner(email);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        expireLeases();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(job(id)).containsEntry("status", "DONE");
        assertThat(email.callsFor(id)).isEqualTo(1);
    }

    @Test
    void aWorkerKilledDuringAnInAppDeliveryIsSafelyRetried() throws Exception {
        String id = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "k");
        inApp.behaviour = c -> { if (c == 1) throw new Error("worker killed"); };
        JobRunner r = runner(inApp);
        drain(r.pollOnce());
        expireLeases();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT");
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, business)).isEqualTo(1);
    }

    @Test
    void aFinishedNoticeIsNotDeliveredAgain() throws Exception {
        String id = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "k");
        drain(runner(inApp).pollOnce());
        NotificationSendHandler handler = new NotificationSendHandler(jdbc, List.of(inApp));
        handler.handle(new NotificationSendPayload(id, id));
        assertThat(inApp.callsFor(id)).isEqualTo(1);
        assertThat(row(id)).containsEntry("attempts", 1);
    }

    @Test
    void aDeliveryInsideATransactionIsRefusedBeforeAnyAttempt() {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        NotificationSendHandler handler = new NotificationSendHandler(jdbc, List.of(email));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> handler.handle(new NotificationSendPayload(id, id))))
                .isInstanceOf(ProviderCallInTransactionException.class);
        assertThat(email.callsFor(id)).isZero();
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
    }

    // ---- concurrency and fencing

    @Test
    void concurrentRunnersDeliverEachNoticeOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k" + i));
        JobRunner r1 = runner(email);
        JobRunner r2 = runner(email);
        ExecutorService pollers = Executors.newFixedThreadPool(2);
        try {
            int guard = 0;
            while (jdbc.queryForObject("select count(*) from job where status <> 'DONE'", Integer.class) > 0 && guard++ < 100) {
                Future<List<Future<?>>> f1 = pollers.submit(r1::pollOnce);
                Future<List<Future<?>>> f2 = pollers.submit(r2::pollOnce);
                drain(f1.get(30, TimeUnit.SECONDS));
                drain(f2.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pollers.shutdownNow();
        }
        for (String id : ids) {
            assertThat(email.callsFor(id)).isEqualTo(1);
            assertThat(row(id)).containsEntry("status", "SENT");
        }
    }

    @Test
    void aStaleWorkerAndItsReplacementDoNotSendAnEmailTwice() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        email.behaviour = c -> { running.countDown(); release.await(30, TimeUnit.SECONDS); };
        JobRunner w1 = runner(email);
        List<Future<?>> stale = w1.pollOnce();
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        expireLeases();
        drain(runner(email).pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(job(id)).containsEntry("status", "DONE").containsEntry("claims", 2);
        release.countDown();
        drain(stale);
        assertThat(email.callsFor(id)).isEqualTo(1);
        assertThat(row(id)).as("a late result can't change a finished notice").containsEntry("status", "FAILED");
    }

    // ---- status operations

    @Test
    void onlyASentInAppNoticeCanBeMarkedRead() throws Exception {
        String app = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "a");
        markRead(app, false);
        assertThat(row(app)).containsEntry("status", "PENDING");
        drain(runner(inApp).pollOnce());
        markRead(app, true);
        assertThat(row(app)).containsEntry("status", "READ");
        markRead(app, false);
    }

    @Test
    void anEmailCantBeMarkedRead() throws Exception {
        String mail = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "m");
        drain(runner(email).pollOnce());
        assertThat(row(mail)).containsEntry("status", "SENT");
        markRead(mail, false);
        assertThat(row(mail)).containsEntry("status", "SENT");
    }

    @Test
    void anotherRestaurantCantMarkANoticeRead() throws Exception {
        String app = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "a");
        drain(runner(inApp).pollOnce());
        assertThat(outbox.markRead(data.tenant("other").businessId(), app)).isFalse();
        assertThat(row(app)).containsEntry("status", "SENT");
    }

    private boolean retry(String id) {
        Boolean retried = new TransactionTemplate(transactions).execute(s -> outbox.retryFailed(id));
        return Boolean.TRUE.equals(retried);
    }

    @Test
    void aControlledRetryRunsAgainOnceThroughANewJob() throws Exception {
        String id = addAndId(NotificationChannel.IN_APP, NotificationKind.EXPIRY_NOTICE, "k");
        inApp.behaviour = c -> { if (c == 1) throw new NotificationNotSentException("rejected", false); };
        JobRunner r = runner(inApp);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(retry(id)).isTrue();
        assertThat(retry(id)).as("already pending").isFalse();
        assertThat(jobs()).isEqualTo(2);
        assertThat(row(id)).containsEntry("status", "PENDING");
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT");
        assertThat(retry(id)).as("a sent notice").isFalse();
    }

    @Test
    void aControlledRetryRefusesAnUncertainEmail() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        email.behaviour = c -> { throw new java.net.SocketTimeoutException("no answer"); };
        drain(runner(email).pollOnce());
        assertThat(retry(id)).isFalse();
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void aControlledRetryIsAllowedForAnEmailKnownNotToHaveLeft() throws Exception {
        String id = addAndId(NotificationChannel.EMAIL, NotificationKind.EXPIRY_NOTICE, "k");
        email.behaviour = c -> { if (c == 1) throw new NotificationNotSentException("bad gateway config", false); };
        JobRunner r = runner(email);
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempts", 0);
        assertThat(retry(id)).isTrue();
        drain(r.pollOnce());
        assertThat(row(id)).containsEntry("status", "SENT");
    }

    @Test
    void theNoticeOutboxUsesARealBusinessForeignKey() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s ->
                outbox.add(UUID.randomUUID().toString(), NotificationKind.EXPIRY_NOTICE, "k", NotificationChannel.IN_APP, Map.of()))).hasMessageContaining("fk_notification_business");
    }
}
