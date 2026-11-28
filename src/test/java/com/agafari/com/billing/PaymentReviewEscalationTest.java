package com.agafari.com.billing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.DeadJobListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF D-3 (register §105, §107 D3-A) on PostgreSQL: a <b>restaurant-less</b> {@code PAYMENT_OUTCOME_PENDING_REVIEW}, from a
 * DEAD payment-event job (gate 2) or an unverified payment outcome (K-1), still open after 48 elapsed hours becomes ESCALATED
 * once, status only, in the hourly sweep: audited, alerted, safe under concurrent sweeps. Its money state is never changed:
 * a verified collection stays verified, an unverified outcome stays unverified. Restaurant-scoped payment reviews and
 * resolved rows are never touched.
 */
class PaymentReviewEscalationTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PENDING_REVIEW = "PAYMENT_OUTCOME_PENDING_REVIEW";

    @Autowired private ExceptionSla sla;
    @Autowired private DeadJobEscalation escalation;
    @Autowired private ObjectMapper mapper;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(ExceptionSla.class)).addAppender(logs);
    }

    @AfterEach
    void stopCapture() {
        ((Logger) LoggerFactory.getLogger(ExceptionSla.class)).detachAppender(logs);
    }

    @Test
    void aGate2PaymentEventReviewEscalatesOnceAt48HoursAndKeepsItsMoneyState() {
        String id = deadPaymentEventReview();
        Map<String, Object> before = row(id);
        Instant created = ((Timestamp) before.get("created_at")).toInstant();

        sla.sweep(created.plus(Duration.ofHours(47)));
        assertThat(row(id).get("status")).as("not before 48 h").isEqualTo("OPEN");

        sla.sweep(created.plus(Duration.ofHours(48)).plusSeconds(60));
        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("ESCALATED");
        assertThat(after.get("escalated_to")).as("status only").isNull();
        assertThat((String) after.get("escalation_reason")).contains("48 elapsed hours").contains("register §105 D-3");
        assertThat(after.get("involves_customer_funds")).as("money state unchanged").isEqualTo(before.get("involves_customer_funds"));
        assertThat(after.get("facts").toString()).isEqualTo(before.get("facts").toString());
        assertThat(audits(id)).isEqualTo(1);
        assertThat(audit(id)).contains("fundsStatus=UNKNOWN");
        assertThat(alerts()).singleElement().satisfies(a -> assertThat(a).contains("id=" + id).contains("code=" + PENDING_REVIEW).contains("fundsStatus=UNKNOWN"));

        sla.sweep(created.plus(Duration.ofHours(72)));
        assertThat(audits(id)).as("once").isEqualTo(1);
    }

    @Test
    void anUnverifiedOutcomeEscalatesButStaysUnverified() {
        String id = unverifiedOutcome();
        Instant created = ((Timestamp) row(id).get("created_at")).toInstant();

        sla.sweep(created.plus(Duration.ofHours(48)).plusSeconds(60));

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("ESCALATED");
        assertThat(after.get("involves_customer_funds")).as("never shown as collected money").isEqualTo(false);
        assertThat(after.get("amount_minor")).isNull();
        assertThat(after.get("provider_payment_ref")).isNull();
        assertThat(facts(after).path("fundsStatus").asString()).isEqualTo("UNVERIFIED");
        assertThat(audit(id)).contains("fundsStatus=UNVERIFIED");
        assertThat(alerts()).singleElement().satisfies(a -> assertThat(a).contains("fundsStatus=UNVERIFIED"));
    }

    @Test
    void aRestaurantScopedPaymentReviewIsNeverEscalatedAutomatically() {
        String checkout = checkout(owner, CheckoutKind.NEW, null);
        String id = deadJobReview("checkout.create", "{\"checkoutId\":\"" + checkout + "\"}");
        assertThat(row(id).get("business_id")).isEqualTo(owner.businessId());

        sla.sweep(Instant.now().plus(Duration.ofHours(72)));

        assertThat(row(id).get("status")).isEqualTo("OPEN");
        assertThat(audits(id)).isZero();
    }

    @Test
    void aResolvedPaymentReviewIsLeftAlone() throws Exception {
        String id = unverifiedOutcome();
        assertThat(act(id, "resolve", "{\"rowVersion\":" + version(id) + ",\"resolution\":\"NO_ACTION_REQUIRED\",\"note\":\"Checked in Stripe: no payment\"}").getStatus())
                .isEqualTo(200);

        sla.sweep(Instant.now().plus(Duration.ofHours(72)));

        assertThat(row(id).get("status")).isEqualTo("RESOLVED");
        assertThat(audits(id)).isZero();
    }

    @Test
    void concurrentHourlySweepsEscalateEachRowOnce() throws Exception {
        List<String> ids = List.of(deadPaymentEventReview(), unverifiedOutcome(), deadPaymentEventReview());
        Map<String, Integer> versions = new java.util.HashMap<>();
        for (String id : ids) versions.put(id, version(id));
        Instant at = Instant.now().plus(Duration.ofHours(49));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> sweeps = new ArrayList<>();
            for (int i = 0; i < 4; i++) sweeps.add(pool.submit(() -> { go.await(); return sla.sweep(at); }));
            go.countDown();
            for (Future<Integer> f : sweeps) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (int i = 1; i <= 3; i++) sla.sweep(at.plus(Duration.ofHours(i)));
        for (String id : ids) {
            assertThat(row(id).get("status")).isEqualTo("ESCALATED");
            assertThat(version(id)).as("one transition").isEqualTo(versions.get(id) + 1);
            assertThat(audits(id)).as("one audit").isEqualTo(1);
        }
        assertThat(alerts()).as("one alert per row").hasSize(3);
    }

    @Test
    void anAdminActingOnTheVersionBeforeEscalationIsRefused() throws Exception {
        String id = unverifiedOutcome();
        int stale = version(id);
        sla.sweep(Instant.now().plus(Duration.ofHours(49)));
        assertThat(act(id, "claim", "{\"rowVersion\":" + stale + "}").getStatus()).isEqualTo(409);
    }

    // ---- fixtures ----

    /** A gate 2 restaurant-less review: a payment-event job that died (the money state is unknown). */
    private String deadPaymentEventReview() {
        String ref = "pay_d3_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of()));
        String event = event(ref);
        return deadJobReview(InboxService.JOB_TYPE, "{\"providerEventId\":\"" + event + "\"}");
    }

    private String deadJobReview(String type, String payload) {
        String job = UUID.randomUUID().toString();
        jdbc.update("insert into job (id, type, key, payload, payload_hash, status, claims, first_attempt_at, finished_at, last_error, last_error_kind) "
                + "values (?, ?, ?, ?::jsonb, ?, 'DEAD', 8, now() - interval '20 hours', now(), 'synthetic failure', 'PERMANENT')", job, type, "d3-" + SEQ.incrementAndGet(), payload, "h" + SEQ.get());
        Map<String, Object> j = jdbc.queryForMap("select id, type, key, payload::text as payload, claims, first_attempt_at, last_error, last_error_kind from job where id = ?", job);
        DeadJobListener.DeadJob dead = new DeadJobListener.DeadJob(job, type, (String) j.get("key"), (String) j.get("payload"), 8, ((Timestamp) j.get("first_attempt_at")).toInstant(),
                (String) j.get("last_error"), (String) j.get("last_error_kind"), DeadJobListener.Path.LOST_WORKER);
        new TransactionTemplate(transactions).executeWithoutResult(s -> escalation.onDead(dead));
        return jdbc.queryForObject("select id from billing_exception where source_job_id = ?", String.class, job);
    }

    /** A K-1 restaurant-less review: the re-fetch named another payment (an unverified outcome). */
    private String unverifiedOutcome() {
        String ref = "pay_d3_vf_" + SEQ.incrementAndGet();
        fakeEt.payment(ref, new VerifiedPayment("pay_d3_other_" + SEQ.get(), ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of()));
        String event = event(ref);
        process(event);
        return jdbc.queryForObject("select id from billing_exception where provider_event_id = ? and code = ?", String.class, event, PENDING_REVIEW);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from billing_exception where id = ?", id);
    }

    private JsonNode facts(Map<String, Object> row) {
        return mapper.readTree(String.valueOf(row.get("facts")));
    }

    private int version(String id) {
        return jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, id);
    }

    private int audits(String id) {
        return count("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_ESCALATED' and target_id = ?", id);
    }

    private String audit(String id) {
        return jdbc.queryForObject("select detail from admin_audit_event where action = 'BILLING_EXCEPTION_ESCALATED' and target_id = ?", String.class, id);
    }

    private List<String> alerts() {
        return logs.list.stream().filter(l -> l.getLevel() == Level.ERROR && l.getFormattedMessage().startsWith("event=billing.exception_escalated"))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private org.springframework.mock.web.MockHttpServletResponse act(String id, String action, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/billing/exceptions/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", data.adminBearer(admin))).andReturn().getResponse();
    }
}
