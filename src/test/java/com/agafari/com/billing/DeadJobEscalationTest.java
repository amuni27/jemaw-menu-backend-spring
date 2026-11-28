package com.agafari.com.billing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxProcessPayload;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.DeadJobListener;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.notifications.NotificationSendPayload;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF gate 2 (register §98, G2-C..G2-J) on PostgreSQL: a financial job in scope that becomes DEAD, by a failed execution or
 * by lost-worker recovery, creates exactly one {@code PAYMENT_OUTCOME_PENDING_REVIEW} exception <b>in the same transaction</b>,
 * attributed only by the job (the V23.6 rule), with the approved deadline, visible in the console, alerted, and recoverable
 * only through the existing actions. Nothing moves money. If the exception can't be stored, the job is not DEAD, never runs
 * again, and keeps alerting until the escalation succeeds.
 */
class DeadJobEscalationTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private DeadJobEscalation escalation;
    @Autowired private RefundService refunds;
    @Autowired private PaymentReview review;
    @Autowired private ExceptionSla sla;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private ObjectMapper mapper;
    @Autowired private InboxProcessHandler inboxHandler;

    private final List<JobRunner> runners = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final MovableClock runnerClock = new MovableClock(Instant.now());

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(JobRunner.class)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger(ExceptionService.class)).addAppender(logs);
    }

    @AfterEach
    void stop() {
        runners.forEach(JobRunner::shutdown);
        ((Logger) LoggerFactory.getLogger(JobRunner.class)).detachAppender(logs);
        ((Logger) LoggerFactory.getLogger(ExceptionService.class)).detachAppender(logs);
        clock.reset();
    }

    // ---- G2-A/G2-C: each type in scope, both DEAD paths ----

    @Test
    void aPaymentEventJobThatDiesByExecutionCreatesOneRestaurantlessExceptionInTheSameTransaction() throws Exception {
        String event = event(paid(checkout(owner, CheckoutKind.NEW, null), micros(Instant.now())));
        String job = jobAtLastClaim(InboxService.JOB_TYPE, "{\"providerEventId\":\"" + event + "\"}");
        MoneyRows before = money();

        drain(runner(failing(InboxService.JOB_TYPE, InboxProcessPayload.class)).pollOnce());

        assertThat(jobStatus(job)).isEqualTo("DEAD");
        Map<String, Object> e = onlyExceptionOf(job);
        assertThat(e.get("business_id")).as("no restaurant: the event body is never evidence").isNull();
        assertThat(e).containsEntry("code", "PAYMENT_OUTCOME_PENDING_REVIEW").containsEntry("provider_event_id", event).containsEntry("involves_customer_funds", true)
                .containsEntry("status", "OPEN");
        assertThat(e.get("checkout_id")).isNull();
        assertThat(dueAfter(e)).as("G2-F: 24 elapsed hours without a restaurant").isEqualTo(Duration.ofHours(24));
        JsonNode facts = facts(e);
        assertThat(facts.path("jobType").asString()).isEqualTo(InboxService.JOB_TYPE);
        assertThat(facts.path("jobId").asString()).isEqualTo(job);
        assertThat(facts.path("deadPath").asString()).isEqualTo("EXECUTION");
        assertThat(facts.path("claims").asInt()).isEqualTo(8);
        assertThat(facts.path("lastErrorKind").asString()).isEqualTo("PERMANENT");
        assertThat(facts.path("lastError").asString()).contains("synthetic failure");
        assertThat(facts.path("eventStatus").asString()).isEqualTo("PENDING");
        assertThat(facts.path("eventType").asString()).isEqualTo("payment.succeeded");
        assertThat(facts.path("eventId").asString()).startsWith("evt_");
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_OPENED' and target_id = ?", Integer.class, e.get("id"))).isEqualTo(1);
        assertThat(lines("event=billing.exception_opened", Level.ERROR)).as("G3-B: ERROR without a restaurant").hasSize(1);
        assertThat(lines("event=job.dead", Level.ERROR)).hasSize(1);
        assertThat(money()).as("nothing moves money").isEqualTo(before);
    }

    @Test
    void aRefundJobThatDiesThroughLostWorkerRecoveryCreatesOneExceptionForItsOwnRestaurant() throws Exception {
        String refund = refundRequest(owner);
        String job = lostWorkerJob(RefundService.JOB_TYPE, "{\"refundRequestId\":\"" + refund + "\"}");
        MoneyRows before = money();

        drain(runner().pollOnce());

        assertThat(jobStatus(job)).isEqualTo("DEAD");
        Map<String, Object> e = onlyExceptionOf(job);
        assertThat(e).containsEntry("business_id", owner.businessId()).containsEntry("refund_request_id", refund).containsEntry("involves_customer_funds", true);
        assertThat(e.get("due_at")).as("G2-F: the SUB-R4-11 SLA").isNotNull();
        assertThat(facts(e).has("sla")).isTrue();
        assertThat(facts(e).path("deadPath").asString()).isEqualTo("LOST_WORKER");
        assertThat(facts(e).path("paymentId").asString()).isEqualTo(jdbc.queryForObject("select payment_id from refund_request where id = ?", String.class, refund));
        assertThat(e.get("amount_minor")).isEqualTo(ET_AMOUNT);
        assertThat(review.underReview(owner.businessId())).as("G2-E: customer funds block new paid checkouts").isTrue();
        assertThat(lines("event=billing.exception_opened", Level.WARN)).hasSize(1);
        assertThat(money()).isEqualTo(before);
    }

    @Test
    void aSubscriptionCancelJobThatDiesIsEscalatedWithItsPayment() throws Exception {
        String payment = charge(owner);
        String job = jobAtLastClaim(ProviderSubscriptionCancelHandler.TYPE, "{\"paymentId\":\"" + payment + "\"}");

        drain(runner(failing(ProviderSubscriptionCancelHandler.TYPE, ProviderSubscriptionCancelHandler.Payload.class)).pollOnce());

        Map<String, Object> e = onlyExceptionOf(job);
        assertThat(e).containsEntry("business_id", owner.businessId()).containsEntry("payment_id", payment).containsEntry("involves_customer_funds", true)
                .containsEntry("amount_minor", ET_AMOUNT).containsEntry("currency", "ETB");
        assertThat(e.get("due_at")).isNotNull();
        assertThat(review.underReview(owner.businessId())).isTrue();
    }

    @Test
    void aCheckoutCreationJobThatDiesGetsA24HourDeadlineButNoMoneyBlock() throws Exception {
        String checkout = checkout(owner, CheckoutKind.NEW, null);
        String job = lostWorkerJob(CheckoutCreateHandler.TYPE, "{\"checkoutId\":\"" + checkout + "\"}");

        drain(runner().pollOnce());

        Map<String, Object> e = onlyExceptionOf(job);
        assertThat(e).containsEntry("business_id", owner.businessId()).containsEntry("checkout_id", checkout).containsEntry("involves_customer_funds", false);
        assertThat(dueAfter(e)).as("G2-E: 24 elapsed hours").isEqualTo(Duration.ofHours(24));
        assertThat(facts(e).path("slaBasis").asString()).contains("24 elapsed hours");
        assertThat(review.underReview(owner.businessId())).as("G2-E: no money block for a failed checkout creation").isFalse();

        Instant due = ((Timestamp) e.get("due_at")).toInstant();
        assertThat(sla.sweep(due.plusSeconds(60))).as("its overdue deadline is flagged and alerted").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_OVERDUE' and target_id = ?", Integer.class, e.get("id"))).isEqualTo(1);
        assertThat(sla.sweep(due.plusSeconds(120))).as("once").isZero();
        assertThat(jdbc.queryForObject("select due_at from billing_exception where id = ?", Timestamp.class, e.get("id")).toInstant()).as("never recomputed to the market SLA")
                .isEqualTo(due);
    }

    // ---- out of scope ----

    @Test
    void aCheckoutExpiryJobThatDiesCreatesNoExceptionAndTheHourlyReconciliationStillClosesItsCheckout() throws Exception {
        String checkout = checkout(owner, CheckoutKind.NEW, null);
        checkouts.cancel(owner.businessId(), checkout);
        String job = jdbc.queryForObject("select id from job where type = ? and key = ?", String.class, CheckoutExpireHandler.TYPE, checkout);
        jdbc.update("update job set claims = 7, run_at = now() - interval '1 second', first_attempt_at = now() - interval '1 hour' where id = ?", job);

        drain(runner(failing(CheckoutExpireHandler.TYPE, CheckoutExpirePayload.class)).pollOnce());

        assertThat(jobStatus(job)).isEqualTo("DEAD");
        assertThat(exceptionsOf(job)).as("G2-C: excluded").isZero();
        jdbc.update("update checkout_session set expires_at = now() - interval '1 minute' where id = ?", checkout);
        reconciliation.hourly(clock.now());
        assertThat(checkoutStatus(checkout)).as("the reconciliation coverage the exclusion relies on").isEqualTo("CANCELLED");
    }

    @Test
    void aNonFinancialJobThatDiesCreatesNoException() throws Exception {
        String job = jobAtLastClaim("notification.send", "{\"notificationId\":\"n-1\",\"jobKey\":\"k\"}");
        drain(runner(failing("notification.send", NotificationSendPayload.class)).pollOnce());
        assertThat(jobStatus(job)).isEqualTo("DEAD");
        assertThat(count("select count(*) from billing_exception")).isZero();
    }

    // ---- at most one per job ----

    @Test
    void twoRunnersRecoveringTheSameDeadJobsCreateExactlyOneExceptionEach() throws Exception {
        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) jobs.add(lostWorkerJob(CheckoutCreateHandler.TYPE, "{\"checkoutId\":\"" + checkout(data.tenant("g2-race-" + i), CheckoutKind.NEW, null) + "\"}"));
        JobRunner a = runner(), b = runner();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<?> fa = pool.submit(() -> { go.await(); return a.pollOnce(); });
            Future<?> fb = pool.submit(() -> { go.await(); return b.pollOnce(); });
            go.countDown();
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        drain(a.pollOnce());
        drain(b.pollOnce());
        for (String job : jobs) {
            assertThat(jobStatus(job)).isEqualTo("DEAD");
            assertThat(exceptionsOf(job)).as(job).isEqualTo(1);
        }
        assertThat(lines("event=billing.exception_opened", null)).hasSize(6);
        assertThat(lines("event=job.dead id=", Level.ERROR)).as("each job became DEAD once").hasSize(6);
    }

    @Test
    void theEscalationIsIdempotentPerJob() throws Exception {
        String checkout = checkout(owner, CheckoutKind.NEW, null);
        String job = lostWorkerJob(CheckoutCreateHandler.TYPE, "{\"checkoutId\":\"" + checkout + "\"}");
        drain(runner().pollOnce());
        Map<String, Object> row = jdbc.queryForMap("select id, type, key, payload::text as payload, claims, first_attempt_at, last_error, last_error_kind from job where id = ?", job);
        DeadJobListener.DeadJob again = new DeadJobListener.DeadJob(job, (String) row.get("type"), (String) row.get("key"), (String) row.get("payload"), 8,
                ((Timestamp) row.get("first_attempt_at")).toInstant(), (String) row.get("last_error"), (String) row.get("last_error_kind"), DeadJobListener.Path.LOST_WORKER);
        new TransactionTemplate(transactions).executeWithoutResult(s -> escalation.onDead(again));
        assertThat(exceptionsOf(job)).as("unique per (job, code)").isEqualTo(1);
    }

    // ---- G2-J: atomic, and safe when the exception can't be stored ----

    @Test
    void whenTheExceptionCantBeStoredTheJobIsNotDeadNeverRunsAgainAndKeepsAlertingUntilItSucceeds() throws Exception {
        String missingEvent = UUID.randomUUID().toString(); // the exception's foreign key to provider_event fails
        String job = jobAtLastClaim(InboxService.JOB_TYPE, "{\"providerEventId\":\"" + missingEvent + "\"}");
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(failing(InboxService.JOB_TYPE, InboxProcessPayload.class, runs));

        drain(r.pollOnce());
        assertThat(runs.get()).isEqualTo(1);
        assertThat(jobStatus(job)).as("atomic: the DEAD update rolled back; the job is parked, not running").isEqualTo("PENDING");
        assertThat(exceptionsOf(job)).isZero();
        assertThat(jdbc.queryForObject("select claims from job where id = ?", Integer.class, job)).as("at the ceiling: never claimed again").isEqualTo(8);
        assertThat(jdbc.queryForObject("select last_error from job where id = ?", String.class, job)).as("the job's own error is kept")
                .isEqualTo("PermanentJobException: synthetic failure");
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR)).as("observable").hasSize(1);
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR).get(0)).contains("id=" + job).contains("path=EXECUTION");

        drain(r.pollOnce());
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR)).as("retried at most once a minute").hasSize(1);
        runnerClock.advance(Duration.ofSeconds(61));
        drain(r.pollOnce());
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR)).as("still failing: alerted again").hasSize(2);
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR).get(1)).contains("path=LOST_WORKER");
        assertThat(jobStatus(job)).isNotEqualTo("DEAD");

        jdbc.update("insert into provider_event (id, provider, event_id, type, payload) values (?, ?, ?, 'payment.succeeded', '{}'::jsonb)", missingEvent, PROVIDER, "evt_g2_" + SEQ.incrementAndGet());
        runnerClock.advance(Duration.ofSeconds(61));
        drain(r.pollOnce());
        assertThat(jobStatus(job)).isEqualTo("DEAD");
        assertThat(exceptionsOf(job)).isEqualTo(1);
        assertThat(runs.get()).as("the handler never ran again").isEqualTo(1);
    }

    /**
     * PO checks (2026-11-25): with the <b>real</b> payment-event handler and a simulated exception-storage outage on both
     * DEAD paths: the parked job is escalated once storage recovers; it becomes DEAD exactly once; exactly one exception;
     * the job never runs again; no payment operation (provider re-fetch) is retried; and each failed attempt left nothing.
     */
    @Test
    void aParkedJobIsEscalatedOnceStorageRecoversWithoutRetryingThePaymentOperation() throws Exception {
        String ref = "pay_g2_pending_" + SEQ.incrementAndGet();
        String checkout = checkout(owner, CheckoutKind.NEW, null);
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.PENDING, new Money(ET_AMOUNT, Currency.ETB), null, metadata(checkout)));
        String event = event(ref);
        String job = jdbc.queryForObject("select id from job where type = ? and payload ->> 'providerEventId' = ?", String.class, InboxService.JOB_TYPE, event);
        jdbc.update("update job set claims = 7, run_at = now() - interval '1 second', first_attempt_at = now() - interval '20 hours' where id = ?", job);
        java.util.concurrent.atomic.AtomicBoolean storageDown = new java.util.concurrent.atomic.AtomicBoolean(true);
        DeadJobListener outage = dead -> {
            if (storageDown.get()) throw new IllegalStateException("synthetic exception-storage outage");
            escalation.onDead(dead);
        };
        JobRunner r = new JobRunner(jdbc, List.of(inboxHandler), mapper, 2, runnerClock, List.of(outage), transactions);
        runners.add(r);
        MoneyRows before = money();
        int verifies = fakeEt.verifyCalls();

        drain(r.pollOnce()); // the 8th claim: the real handler re-fetches once, the payment is pending, the job dies; storage is down
        assertThat(fakeEt.verifyCalls() - verifies).as("the payment was re-fetched once by the job's last run").isEqualTo(1);
        assertThat(jobStatus(job)).as("execution path rolled back: parked").isEqualTo("PENDING");
        assertThat(exceptionsOf(job)).isZero();

        runnerClock.advance(Duration.ofSeconds(61));
        drain(r.pollOnce()); // lost-worker path retries the escalation; storage still down
        assertThat(jobStatus(job)).as("lost-worker path rolled back too").isEqualTo("PENDING");
        assertThat(exceptionsOf(job)).isZero();
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR)).hasSize(2);

        storageDown.set(false);
        runnerClock.advance(Duration.ofSeconds(61));
        drain(r.pollOnce());
        assertThat(jobStatus(job)).as("escalated once storage recovered").isEqualTo("DEAD");
        assertThat(exceptionsOf(job)).as("exactly one exception").isEqualTo(1);
        assertThat(onlyExceptionOf(job)).containsEntry("provider_event_id", event).containsEntry("involves_customer_funds", true);

        for (int i = 0; i < 3; i++) {
            runnerClock.advance(Duration.ofMinutes(5));
            drain(r.pollOnce());
        }
        assertThat(lines("event=job.dead id=" + job, Level.ERROR)).as("DEAD exactly once").hasSize(1);
        assertThat(exceptionsOf(job)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select claims from job where id = ?", Integer.class, job)).as("never claimed again").isEqualTo(8);
        assertThat(fakeEt.verifyCalls() - verifies).as("escalation retries never retried the payment operation").isEqualTo(1);
        assertThat(eventRow(event).get("status")).as("the payment event itself is untouched").isEqualTo("PENDING");
        assertThat(money()).as("no payment, invoice, period, subscription, refund or checkout changed").isEqualTo(before);
    }

    @Test
    void aJobThatHitsTheTransientCapBelowTheCeilingAndCantBeEscalatedIsNeverRunAgain() throws Exception {
        String job = UUID.randomUUID().toString();
        jdbc.update("insert into job (id, type, key, payload, payload_hash, status, claims, run_at, first_attempt_at) "
                + "values (?, ?, ?, ?::jsonb, ?, 'PENDING', 2, now() - interval '1 second', now() - interval '73 hours')",
                job, InboxService.JOB_TYPE, "g2-" + SEQ.incrementAndGet(), "{\"providerEventId\":\"" + UUID.randomUUID() + "\"}", "h" + SEQ.get());
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(new JobHandler<InboxProcessPayload>() {
            @Override public String type() { return InboxService.JOB_TYPE; }
            @Override public Class<InboxProcessPayload> payloadType() { return InboxProcessPayload.class; }
            @Override public void handle(InboxProcessPayload p) {
                runs.incrementAndGet();
                throw new com.agafari.com.jobs.TransientJobException("synthetic timeout", null);
            }
        });

        drain(r.pollOnce());
        assertThat(jobStatus(job)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select claims from job where id = ?", Integer.class, job)).as("parked at the ceiling, though it died at claim 3").isEqualTo(8);
        runnerClock.advance(Duration.ofSeconds(61));
        drain(r.pollOnce());
        drain(r.pollOnce());
        assertThat(runs.get()).as("a job that died is never executed again").isEqualTo(1);
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR)).hasSize(2);
    }

    @Test
    void aListenerFailureOnTheLostWorkerPathAlsoRollsBackTheDeadUpdate() throws Exception {
        String job = lostWorkerJob(InboxService.JOB_TYPE, "{\"providerEventId\":\"" + UUID.randomUUID() + "\"}");
        drain(runner().pollOnce());
        assertThat(jobStatus(job)).isNotEqualTo("DEAD");
        assertThat(exceptionsOf(job)).isZero();
        assertThat(lines("event=job.dead_escalation_failed", Level.ERROR)).hasSize(1);
    }

    @Test
    void theRunnerCallsListenersInsideTheDeadTransaction() throws Exception {
        String job = jobAtLastClaim("notification.send", "{\"notificationId\":\"n-2\",\"jobKey\":\"k2\"}");
        List<String> seenStatus = new ArrayList<>();
        DeadJobListener probe = dead -> seenStatus.add(jdbc.queryForObject("select status from job where id = ?", String.class, dead.id()));
        JobRunner r = new JobRunner(jdbc, List.of(failing("notification.send", NotificationSendPayload.class)), mapper, 2, runnerClock, List.of(probe), transactions);
        runners.add(r);
        drain(r.pollOnce());
        assertThat(seenStatus).as("the listener sees its own transaction's DEAD row").containsExactly("DEAD");
    }

    // ---- visibility and the existing recovery ----

    @Test
    void theExceptionIsVisibleInTheConsoleAndReplayRecoversThePaymentOnce() throws Exception {
        String event = event(paid(checkout(owner, CheckoutKind.NEW, null), micros(Instant.now())));
        String job = lostWorkerJob(InboxService.JOB_TYPE, "{\"providerEventId\":\"" + event + "\"}");
        drain(runner().pollOnce());
        String id = (String) onlyExceptionOf(job).get("id");

        JsonNode summary = getJson("/api/admin/billing/exceptions/summary");
        assertThat(summary.path("unresolved").asLong()).isEqualTo(1);
        JsonNode list = getJson("/api/admin/billing/exceptions?state=UNRESOLVED");
        assertThat(list.toString()).contains(id).contains(job);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse r = mockMvc.perform(post("/api/admin/billing/exceptions/" + id + "/replay").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rowVersion\":" + version(id) + "}").header("Authorization", data.adminBearer(admin))).andReturn().getResponse();
            assertThat(r.getStatus()).isEqualTo(200);
        }
        assertThat(payments(owner)).as("G2-I: the existing replay applies the payment once").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from billing_exception where id = ?", String.class, id)).as("never resolved automatically").isNotEqualTo("RESOLVED");
        assertThat(jobStatus(job)).as("no job is re-run").isEqualTo("DEAD");
    }

    // ---- fixtures ----

    private JobRunner runner(JobHandler<?>... handlers) {
        JobRunner r = new JobRunner(jdbc, List.of(handlers), mapper, 2, runnerClock, List.of(escalation), transactions);
        runners.add(r);
        return r;
    }

    private <T> JobHandler<T> failing(String type, Class<T> payloadType) {
        return failing(type, payloadType, new AtomicInteger());
    }

    private <T> JobHandler<T> failing(String type, Class<T> payloadType, AtomicInteger runs) {
        return new JobHandler<>() {
            @Override public String type() { return type; }
            @Override public Class<T> payloadType() { return payloadType; }
            @Override public void handle(T payload) {
                runs.incrementAndGet();
                throw new PermanentJobException("synthetic failure", null);
            }
        };
    }

    /** A job on its last claim: the next run's failure makes it DEAD by execution. */
    private String jobAtLastClaim(String type, String payload) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into job (id, type, key, payload, payload_hash, status, claims, run_at, first_attempt_at, last_error, last_error_kind) "
                + "values (?, ?, ?, ?::jsonb, ?, 'PENDING', 7, now() - interval '1 second', now() - interval '20 hours', 'previous synthetic failure', 'PERMANENT')",
                id, type, "g2-" + SEQ.incrementAndGet(), payload, "h" + SEQ.get());
        return id;
    }

    /** A job whose worker was lost at the claim ceiling: lease recovery makes it DEAD. */
    private String lostWorkerJob(String type, String payload) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into job (id, type, key, payload, payload_hash, status, claims, run_at, first_attempt_at, lease_until, claim_token, last_error, last_error_kind) "
                + "values (?, ?, ?, ?::jsonb, ?, 'RUNNING', 8, now() - interval '1 hour', now() - interval '20 hours', now() - interval '1 minute', 'lost', 'synthetic failure', 'TRANSIENT')",
                id, type, "g2-" + SEQ.incrementAndGet(), payload, "h" + SEQ.get());
        return id;
    }

    /** A verified charge from a clean activation: no exception exists before the job under test dies. */
    private String charge(Tenant t) {
        activate(t, micros(Instant.now()));
        assertThat(count("select count(*) from billing_exception")).as("the fixture opens no exception").isZero();
        logs.list.clear();
        return jdbc.queryForObject("select id from payment where business_id = ? and kind = 'CHARGE'", String.class, t.businessId());
    }

    private String refundRequest(Tenant t) {
        String payment = charge(t);
        String id = new TransactionTemplate(transactions).execute(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            return refunds.request(t.businessId(), payment, "TEST", "g2");
        });
        jdbc.update("delete from job where type = ? and key = ?", RefundService.JOB_TYPE, id); // the fixture's own job is replaced by the test's
        return id;
    }

    private static void drain(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
    }

    private String jobStatus(String id) {
        return jdbc.queryForObject("select status from job where id = ?", String.class, id);
    }

    private int exceptionsOf(String jobId) {
        return count("select count(*) from billing_exception where source_job_id = ?", jobId);
    }

    private Map<String, Object> onlyExceptionOf(String jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_exception where source_job_id = ?", jobId);
        assertThat(rows).as("exactly one exception for job " + jobId).hasSize(1);
        return rows.get(0);
    }

    private JsonNode facts(Map<String, Object> e) {
        return mapper.readTree(String.valueOf(e.get("facts")));
    }

    private static Duration dueAfter(Map<String, Object> e) {
        return Duration.between(((Timestamp) e.get("created_at")).toInstant(), ((Timestamp) e.get("due_at")).toInstant());
    }

    private int version(String id) {
        return jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, id);
    }

    private JsonNode getJson(String path) throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get(path).header("Authorization", data.adminBearer(admin))).andReturn().getResponse();
        assertThat(r.getStatus()).as(path).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    private List<String> lines(String prefix, Level level) {
        return logs.list.stream().filter(l -> l.getFormattedMessage().startsWith(prefix) && (level == null || l.getLevel() == level)).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Payments, invoices, periods, subscriptions, refund requests and checkouts, by status: what must never change. */
    private record MoneyRows(List<Map<String, Object>> rows) {
    }

    private MoneyRows money() {
        return new MoneyRows(jdbc.queryForList("""
                select 'payment' as t, status as s, count(*) as n from payment group by status
                union all select 'invoice', status, count(*) from billing_invoice group by status
                union all select 'period', '', count(*) from subscription_period
                union all select 'subscription', status, count(*) from business_subscription group by status
                union all select 'refund', status, count(*) from refund_request group by status
                union all select 'checkout', status, count(*) from checkout_session group by status
                order by 1, 2"""));
    }

    /** The runner's own clock, moved by the test (the escalation retry interval). */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
