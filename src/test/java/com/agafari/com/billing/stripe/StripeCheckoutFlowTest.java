package com.agafari.com.billing.stripe;

import com.agafari.com.billing.CheckoutService;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcome;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.ProviderSubscriptionCancelHandler;
import com.agafari.com.billing.stripe.StripeFixtures.Paid;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.Actor;
import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R5-02 on PostgreSQL: the real checkout API, the real Stripe adapter and SDK over a stubbed HTTP layer with
 * <b>synthetic</b> fixtures ({@link StripeFixtures#SYNTHETIC}), signed webhooks (synthetic secret), the inbox, the outcome
 * matrix, the ledger and evidence triggers, and the D4 cancellation job. No network call; no real Stripe account.
 */
@Import(StripeStubBillingConfig.class)
class StripeCheckoutFlowTest extends AbstractIntegrationTest {

    private static final AtomicInteger KEYS = new AtomicInteger();
    private static final long AMOUNT = 2500;
    private static final String PRICE_REF = "price_synthetic_m4";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StripeStub stub;
    @Autowired private CheckoutService checkouts;
    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private WebhookController webhooks;
    @Autowired private ProviderSubscriptionCancelHandler cancelHandler;
    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private MonetizationLocks locks;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper mapper;

    private User admin;
    private String priceId;

    @BeforeEach
    void setUp() {
        stub.reset();
        checkouts.setBillingEnabled(Market.US, true);
        outcomes.setBillingEnabled(Market.US, true);
        webhooks.setBillingEnabled(Market.US, true);
        admin = data.admin("r502-admin");
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (1, 'SETTING_2', true, false, true, now() - interval '1 day', now(), ?)", admin.getId());
        priceId = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'US', 'USD', 'MONTH', ?, 1, ?, true, now() - interval '1 day')", priceId, AMOUNT, PRICE_REF);
    }

    @AfterEach
    void tearDown() {
        checkouts.setBillingEnabled(Market.US, false);
        outcomes.setBillingEnabled(Market.US, false);
        webhooks.setBillingEnabled(Market.US, false);
    }

    // ---- helpers ----

    private Tenant usTenant(String label) {
        Tenant t = data.tenant(label);
        jdbc.update("update business set country = 'US' where id = ?", t.businessId());
        return t;
    }

    private MockHttpServletResponse requestCheckout(Tenant t) throws Exception {
        String sessionRef = StripeFixtures.id("cs");
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.createdSession(sessionRef)));
        return mockMvc.perform(post("/api/billing/checkouts").contentType(MediaType.APPLICATION_JSON).content("{\"priceId\":\"" + priceId + "\"}")
                .header("Idempotency-Key", "r502-" + KEYS.incrementAndGet()).header("Authorization", data.bearer(t))).andReturn().getResponse();
    }

    private String checkout(Tenant t) throws Exception {
        assertThat(requestCheckout(t).getStatus()).isEqualTo(201);
        return jdbc.queryForObject("select id from checkout_session where business_id = ? and status = 'OPEN'", String.class, t.businessId());
    }

    /** The provider objects of a payment for {@code checkoutId}, paid at {@code paidAt}, with a period from a minute ago. */
    private Paid paid(Tenant t, String checkoutId, Instant paidAt) {
        String sessionRef = jdbc.queryForObject("select provider_session_ref from checkout_session where id = ?", String.class, checkoutId);
        Paid p = new Paid(checkoutId, t.businessId(), sessionRef, PRICE_REF, AMOUNT, Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS), paidAt);
        StripeFixtures.register(stub, p);
        return p;
    }

    private Paid paid(Tenant t, String checkoutId) {
        return paid(t, checkoutId, Instant.now().minusSeconds(30));
    }

    /** Delivers a signed event (synthetic secret) through the real webhook; returns the stored event's row id. */
    private String deliver(String payload) throws Exception {
        String header = Webhook.Signature.generateSignatureHeader(payload, StripeStubBillingConfig.WEBHOOK_SECRET);
        int status = mockMvc.perform(post("/api/billing/webhooks/STRIPE").contentType(MediaType.APPLICATION_JSON).content(payload).header("Stripe-Signature", header))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        String eventId = mapper.readTree(payload).path("id").asString();
        return jdbc.queryForObject("select id from provider_event where provider = 'STRIPE' and event_id = ?", String.class, eventId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int payments(Tenant t) {
        return count("select count(*) from payment where business_id = ?", t.businessId());
    }

    private Map<String, Object> exceptionOf(Tenant t) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_exception where business_id = ?", t.businessId());
        assertThat(rows).as("exactly one exception").hasSize(1);
        return rows.get(0);
    }

    private int cancelJobs(String paymentId) {
        return count("select count(*) from job where type = ? and key = ?", ProviderSubscriptionCancelHandler.TYPE, "payment:" + paymentId);
    }

    private int audits(String action, String target) {
        return count("select count(*) from admin_audit_event where action = ? and target_id = ?", action, target);
    }

    private String paymentId(Paid p) {
        return jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, p.invoiceRef);
    }

    private void assertActivatedFromStripe(Tenant t, Paid p) {
        Map<String, Object> pay = jdbc.queryForMap("select * from payment where business_id = ?", t.businessId());
        assertThat(pay).containsEntry("provider", "STRIPE").containsEntry("provider_payment_ref", p.invoiceRef).containsEntry("kind", "CHARGE").containsEntry("status", "SUCCEEDED");
        assertThat(((Timestamp) pay.get("occurred_at")).toInstant()).isEqualTo(Instant.ofEpochSecond(p.paidAt));
        Map<String, Object> inv = jdbc.queryForMap("select * from billing_invoice where business_id = ?", t.businessId());
        assertThat(inv).containsEntry("provider_invoice_ref", p.invoiceRef).containsEntry("status", "PAID").containsEntry("amount_minor", AMOUNT).containsEntry("currency", "USD");
        assertThat(((Timestamp) inv.get("period_start")).toInstant()).as("PO Q2").isEqualTo(Instant.ofEpochSecond(p.periodStart));
        assertThat(((Timestamp) inv.get("period_end")).toInstant()).isEqualTo(Instant.ofEpochSecond(p.periodEnd));
        Map<String, Object> sub = jdbc.queryForMap("select * from business_subscription where business_id = ? and status <> 'EXPIRED'", t.businessId());
        assertThat(sub).containsEntry("source", "PROVIDER").containsEntry("provider", "STRIPE").containsEntry("plan_code", "MODEL_4").containsEntry("provider_subscription_ref", p.subscriptionRef);
        assertThat(((Timestamp) sub.get("period_end")).toInstant()).isEqualTo(Instant.ofEpochSecond(p.periodEnd));
        Map<String, Object> period = jdbc.queryForMap("select * from subscription_period where subscription_id = ?", sub.get("id"));
        assertThat(((Timestamp) period.get("period_start")).toInstant()).isEqualTo(Instant.ofEpochSecond(p.periodStart));
        assertThat(jdbc.queryForObject("select provider_customer_ref from billing_account where business_id = ? and provider = 'STRIPE'", String.class, t.businessId()))
                .as("PO U5: stored once").isEqualTo(p.customerRef);
        assertThat(count("select count(*) from billing_exception where business_id = ?", t.businessId())).isZero();
    }

    // ---- catalogue: checkout, both orders, exactly once (U1, D6, Q2) ----

    @Test
    void aNewCheckoutIsCreatedAtStripeAndBothInitialEventsActivateItOnceSessionFirst() throws Exception {
        Tenant t = usTenant("r502-new");
        String c = checkout(t);
        StripeStub.Call create = stub.calls("POST", "/v1/checkout/sessions").get(0);
        assertThat(create.idempotencyKey()).isEqualTo("checkout:" + c);
        assertThat(create.params()).doesNotContainKey("customer");
        Paid p = paid(t, c);

        assertThat(outcomes.process(deliver(p.checkoutCompletedEvent()))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertActivatedFromStripe(t, p);
        Map<String, Object> state = jdbc.queryForMap("select (select count(*) from payment where business_id = ?) p, (select count(*) from subscription_period where business_id = ?) sp",
                t.businessId(), t.businessId());
        assertThat(outcomes.process(deliver(p.invoicePaidEvent()))).as("the second event finds the ledger row").isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(jdbc.queryForMap("select (select count(*) from payment where business_id = ?) p, (select count(*) from subscription_period where business_id = ?) sp",
                t.businessId(), t.businessId())).isEqualTo(state);
        assertThat(jdbc.queryForObject("select status from checkout_session where id = ?", String.class, c)).isEqualTo("COMPLETED");
    }

    @Test
    void invoicePaidArrivingFirstActivatesOnceToo() throws Exception {
        Tenant t = usTenant("r502-invoice-first");
        Paid p = paid(t, checkout(t));
        outcomes.process(deliver(p.invoicePaidEvent()));
        outcomes.process(deliver(p.checkoutCompletedEvent()));
        assertActivatedFromStripe(t, p);
        assertThat(payments(t)).isEqualTo(1);
    }

    @Test
    void duplicateAndConcurrentDeliveryHasOneFinancialEffect() throws Exception {
        Tenant t = usTenant("r502-concurrent");
        Paid p = paid(t, checkout(t));
        String completed = p.checkoutCompletedEvent();
        String a = deliver(completed);
        assertThat(deliver(completed)).as("the same event id is stored once").isEqualTo(a);
        String b = deliver(p.invoicePaidEvent());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<PaymentOutcomeService.Result>> runs = new ArrayList<>();
            for (String e : List.of(a, b)) runs.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return outcomes.process(e);
            }));
            for (Future<PaymentOutcomeService.Result> r : runs) r.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertActivatedFromStripe(t, p);
        assertThat(payments(t)).isEqualTo(1);
        assertThat(count("select count(*) from subscription_period where business_id = ?", t.businessId())).isEqualTo(1);
    }

    // ---- D1: Free with Ads → paid ----

    @Test
    void freeWithAdsIsReplacedOnlyAfterTheVerifiedPayment() throws Exception {
        Tenant t = usTenant("r502-free");
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.startOwnerFree(t.businessId(), "MODEL_2", 1, Instant.now(), new Actor(t.owner().getId()));
        });
        String free = jdbc.queryForObject("select id from business_subscription where business_id = ?", String.class, t.businessId());
        String c = checkout(t);
        assertThat(jdbc.queryForMap("select kind, replaces_subscription_id from checkout_session where id = ?", c)).containsEntry("kind", "UPGRADE").containsEntry("replaces_subscription_id", free);
        Paid p = paid(t, c);
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, free)).as("nothing before payment").isEqualTo("ACTIVE");

        outcomes.process(deliver(p.checkoutCompletedEvent()));

        Map<String, Object> old = jdbc.queryForMap("select status, ended_reason, replaced_by_id from business_subscription where id = ?", free);
        assertThat(old).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED");
        assertThat(old.get("replaced_by_id")).isEqualTo(jdbc.queryForObject("select id from business_subscription where provider_subscription_ref = ?", String.class, p.subscriptionRef));
    }

    @Test
    void conversionAndPlanChangesAreRefusedBeforeAnythingIsReserved() throws Exception {
        Tenant trial = usTenant("r502-trial");
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(trial.businessId());
            lifecycle.startTrial(trial.businessId(), 1, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS), new Actor(trial.owner().getId()));
        });
        MockHttpServletResponse r = requestCheckout(trial);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentAsString()).contains("CHECKOUT_CONVERSION_UNAVAILABLE");

        Tenant paidOne = usTenant("r502-paid");
        Paid p = paid(paidOne, checkout(paidOne));
        outcomes.process(deliver(p.checkoutCompletedEvent()));
        r = requestCheckout(paidOne);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentAsString()).contains("CHECKOUT_PLAN_CHANGE_UNAVAILABLE");
        assertThat(count("select count(*) from checkout_session where business_id in (?, ?) and status in ('CREATING', 'OPEN')", trial.businessId(), paidOne.businessId())).isZero();
    }

    // ---- D6: not an initial invoice; not correlated ----

    @Test
    void aRenewalInvoiceIsQuarantinedWithoutAnyFinancialEffect() throws Exception {
        Tenant t = usTenant("r502-renewal");
        Paid p = paid(t, checkout(t));
        outcomes.process(deliver(p.checkoutCompletedEvent()));
        Paid renewal = paid(t, jdbc.queryForObject("select id from checkout_session where business_id = ?", String.class, t.businessId()));
        renewal.invoice.put("billing_reason", "subscription_cycle");

        assertThat(outcomes.process(deliver(renewal.invoicePaidEvent()))).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);

        assertThat(payments(t)).as("no ledger row").isEqualTo(1);
        assertThat(count("select count(*) from billing_exception where business_id = ?", t.businessId())).as("no exception").isZero();
        assertThat(count("select count(*) from refund_request")).as("no refund").isZero();
    }

    @Test
    void anUncorrelatedOrForeignInitialInvoiceIsQuarantined() throws Exception {
        Tenant t = usTenant("r502-uncorrelated"), other = usTenant("r502-other");
        Paid p = paid(t, checkout(t));
        stub.on("GET", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.json(StripeFixtures.list(List.of()))));
        assertThat(outcomes.process(deliver(p.invoicePaidEvent()))).as("row 1: no session").isEqualTo(PaymentOutcomeService.Result.QUARANTINED);

        Paid q = paid(t, jdbc.queryForObject("select id from checkout_session where business_id = ?", String.class, t.businessId()));
        ((Map<String, Object>) q.session.get("metadata")).put("agafari_business_id", other.businessId()); // tenant isolation
        assertThat(outcomes.process(deliver(q.invoicePaidEvent()))).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat(payments(t) + payments(other)).isZero();
        assertThat(count("select count(*) from business_subscription where business_id in (?, ?)", t.businessId(), other.businessId())).isZero();
    }

    // ---- row 0: not qualifying evidence (U1) ----

    @Test
    void nonQualifyingEvidenceIsRow0WithNoChargeNoActivationAndNoCancellation() throws Exception {
        Tenant t = usTenant("r502-row0");
        Paid p = paid(t, checkout(t));
        p.charge().put("payment_method_details", Map.of("type", "us_bank_account"));

        outcomes.process(deliver(p.checkoutCompletedEvent()));

        assertThat(payments(t)).as("no SUCCEEDED CHARGE before evidence qualifies").isZero();
        Map<String, Object> ex = exceptionOf(t);
        assertThat(ex).containsEntry("code", PaymentOutcome.VERIFICATION_FAILED).containsEntry("status", "OPEN");
        assertThat(ex.get("facts").toString()).contains("providerBillingProblem");
        assertThat(count("select count(*) from business_subscription where business_id = ?", t.businessId())).isZero();
        assertThat(count("select count(*) from job where type = ?", ProviderSubscriptionCancelHandler.TYPE)).as("PO U3: row 0 is never cancelled").isZero();
        assertThat(count("select count(*) from admin_audit_event where action = 'PROVIDER_SUBSCRIPTION_REVIEW_REQUIRED' and detail like ?", "%" + p.invoiceRef + "%")).isEqualTo(1);
    }

    /** U1/Q1: a fully qualifying Stripe payment that isn't the quoted amount, price or interval is row 0: no charge, no activation. */
    @Test
    void aPaymentForAnotherAmountPriceOrIntervalThanTheQuoteIsRow0() throws Exception {
        Tenant amount = usTenant("r502-amount");
        String ca = checkout(amount);
        Paid pa = new Paid(ca, amount.businessId(), jdbc.queryForObject("select provider_session_ref from checkout_session where id = ?", String.class, ca), PRICE_REF,
                AMOUNT + 100, Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS), Instant.now().minusSeconds(30));
        StripeFixtures.register(stub, pa);
        assertRow0(amount, pa, null);

        Tenant price = usTenant("r502-price");
        String cp = checkout(price);
        Paid pp = new Paid(cp, price.businessId(), jdbc.queryForObject("select provider_session_ref from checkout_session where id = ?", String.class, cp), "price_" + "synthetic_other",
                AMOUNT, Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS), Instant.now().minusSeconds(30));
        StripeFixtures.register(stub, pp);
        assertRow0(price, pp, "quoted price");

        Tenant interval = usTenant("r502-interval");
        Paid pi = paid(interval, checkout(interval));
        pi.recurring().put("interval", "year");
        assertRow0(interval, pi, "quoted interval");

        assertThat(count("select count(*) from job where type = ?", ProviderSubscriptionCancelHandler.TYPE)).as("row 0 is never cancelled automatically").isZero();
    }

    private void assertRow0(Tenant t, Paid p, String problem) throws Exception {
        assertThat(outcomes.process(deliver(p.checkoutCompletedEvent()))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(payments(t)).as("no SUCCEEDED CHARGE").isZero();
        Map<String, Object> ex = exceptionOf(t);
        assertThat(ex).containsEntry("code", PaymentOutcome.VERIFICATION_FAILED).containsEntry("status", "OPEN");
        if (problem != null) assertThat(ex.get("facts").toString()).contains("providerBillingProblem").contains(problem);
        assertThat(count("select count(*) from business_subscription where business_id = ?", t.businessId())).isZero();
    }

    // ---- rows 3, 4, 5: terminal non-activation → cancellation job (U2) ----

    private Paid paidAfterCancel(Tenant t) throws Exception {
        String c = checkout(t);
        mockMvc.perform(post("/api/billing/checkouts/{id}/cancel", c).header("Authorization", data.bearer(t))).andReturn();
        Instant cancelledAt = jdbc.queryForObject("select cancel_requested_at from checkout_session where id = ?", Timestamp.class, c).toInstant();
        Paid p = paid(t, c, cancelledAt.plusSeconds(5).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        outcomes.process(deliver(p.checkoutCompletedEvent()));
        return p;
    }

    @Test
    void row4PaidAfterCancelEnqueuesCancellationAtomicallyAndTheJobCancelsImmediately() throws Exception {
        Tenant t = usTenant("r502-row4");
        Paid p = paidAfterCancel(t);
        Map<String, Object> ex = exceptionOf(t);
        assertThat(ex).as("Stripe refunds aren't confirmed yet (R5-05): a manual-refund review").containsEntry("code", PaymentOutcome.REFUND_REQUIRED_MANUAL).containsEntry("status", "OPEN");
        String pay = paymentId(p);
        assertThat(cancelJobs(pay)).as("enqueued with the decision").isEqualTo(1);
        assertThat(jdbc.queryForObject("select (select xmin::text from billing_exception where payment_id = ?) = (select xmin::text from job where type = ? and key = ?)",
                Boolean.class, pay, ProviderSubscriptionCancelHandler.TYPE, "payment:" + pay)).as("U2: in the decision's own transaction").isTrue();
        assertThat(count("select count(*) from business_subscription where business_id = ?", t.businessId())).isZero();
        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> {
            p.subscription.put("status", "canceled");
            return StripeStub.ok(StripeFixtures.json(p.subscription));
        });

        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay));

        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + p.subscriptionRef)).hasSize(1).allSatisfy(c -> assertThat(c.params()).containsEntry("invoice_now", false).containsEntry("prorate", false));
        assertThat(audits("PROVIDER_SUBSCRIPTION_CANCELLED", p.subscriptionRef)).isEqualTo(1);
        assertThat(exceptionOf(t)).as("code, status and facts unchanged").containsEntry("code", ex.get("code")).containsEntry("status", "OPEN").containsEntry("facts", ex.get("facts"));
        assertThat(count("select count(*) from refund_request")).as("no refund outside the matrix").isZero();

        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay)); // a duplicate or stale run
        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + p.subscriptionRef)).as("re-fetched: no second cancellation").hasSize(1);
        assertThat(audits("PROVIDER_SUBSCRIPTION_ALREADY_CANCELLED", p.subscriptionRef)).isEqualTo(1);
    }

    @Test
    void row3AndRow5AlsoEnqueueCancellation() throws Exception {
        Tenant late = usTenant("r502-row3");
        String c = checkout(late);
        Instant expires = jdbc.queryForObject("select expires_at from checkout_session where id = ?", Timestamp.class, c).toInstant();
        Paid p3 = paid(late, c, expires.plusSeconds(60).truncatedTo(ChronoUnit.SECONDS));
        outcomes.process(deliver(p3.checkoutCompletedEvent()));
        assertThat(exceptionOf(late).get("facts").toString()).contains("\"row\": 3");
        assertThat(cancelJobs(paymentId(p3))).isEqualTo(1);

        Tenant ineligible = usTenant("r502-row5");
        String c5 = checkout(ineligible);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (2, 'SETTING_2', true, false, false, now() + interval '10 seconds', now(), ?)", admin.getId());
        Paid p5 = paid(ineligible, c5, Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS));
        outcomes.process(deliver(p5.checkoutCompletedEvent()));
        assertThat(exceptionOf(ineligible).get("facts").toString()).contains("\"row\": 5");
        assertThat(cancelJobs(paymentId(p5))).isEqualTo(1);
    }

    // ---- row 6 and D3: never cancelled automatically (U3) ----

    @Test
    void row6IsAReviewNotACancellation() throws Exception {
        Tenant t = usTenant("r502-row6");
        Paid p = paid(t, checkout(t));
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.startOwnerFree(t.businessId(), "MODEL_2", 1, Instant.now(), new Actor(t.owner().getId()));
        });
        outcomes.process(deliver(p.checkoutCompletedEvent()));
        assertThat(exceptionOf(t)).containsEntry("code", PaymentOutcome.STATE_CHANGED).containsEntry("status", "OPEN");
        assertThat(payments(t)).as("the charge is accounted for").isEqualTo(1);
        assertThat(cancelJobs(paymentId(p))).isZero();
        assertThat(audits("PROVIDER_SUBSCRIPTION_REVIEW_REQUIRED", p.subscriptionRef)).isEqualTo(1);
    }

    @Test
    void anAlreadyEndedProviderPeriodIsRefusedWithItsEvidenceKept() throws Exception {
        Tenant t = usTenant("r502-ended");
        Paid p = paid(t, checkout(t));
        long start = Instant.now().minus(40, ChronoUnit.DAYS).getEpochSecond(), end = Instant.now().minus(10, ChronoUnit.DAYS).getEpochSecond();
        ((Map<String, Object>) p.line().get("period")).putAll(Map.of("start", start, "end", end));
        p.item().putAll(Map.of("current_period_start", start, "current_period_end", end));

        outcomes.process(deliver(p.checkoutCompletedEvent()));

        assertThat(exceptionOf(t)).containsEntry("code", PaymentOutcomeService.EVIDENCE_INVALID);
        assertThat(payments(t)).as("PO D3: evidence preserved").isEqualTo(1);
        assertThat(count("select count(*) from business_subscription where business_id = ?", t.businessId())).isZero();
        assertThat(count("select count(*) from job where type = ?", ProviderSubscriptionCancelHandler.TYPE)).isZero();
        assertThat(audits("PROVIDER_SUBSCRIPTION_REVIEW_REQUIRED", p.subscriptionRef)).isEqualTo(1);
    }

    // ---- D4 checks C1–C6, failures and uncertainty ----

    private int handedBack(Tenant t) {
        return count("select count(*) from admin_audit_event where action in ('PROVIDER_SUBSCRIPTION_HANDED_BACK', 'BILLING_EXCEPTION_HANDED_BACK') and target_id = ?",
                exceptionOf(t).get("id"));
    }

    @Test
    void anyFailedSafetyCheckHandsTheExistingExceptionBackWithoutCancelling() throws Exception {
        Tenant c2 = usTenant("r502-c2");
        Paid p2 = paidAfterCancel(c2);
        ((Map<String, Object>) p2.session.get("metadata")).put("agafari_business_id", "someone-else");
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(p2)));
        assertThat(handedBack(c2)).as("C2 ownership").isEqualTo(1);

        Tenant c3 = usTenant("r502-c3");
        Paid p3 = paidAfterCancel(c3);
        p3.subscription.put("livemode", true);
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(p3)));
        assertThat(handedBack(c3)).as("C3 live mode").isEqualTo(1);

        Tenant c5 = usTenant("r502-c5");
        Paid p5 = paidAfterCancel(c5);
        p5.openInvoices.add(Map.of("id", "in_other_open", "object", "invoice", "status", "open",
                "parent", Map.of("type", "subscription_details", "subscription_details", Map.of("subscription", "sub_other"))));
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(p5)));
        assertThat(handedBack(c5)).as("C5 other open invoices").isEqualTo(1);

        Tenant c6 = usTenant("r502-c6");
        Paid p6 = paidAfterCancel(c6);
        p6.subscription.put("status", "trialing");
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(p6)));
        assertThat(handedBack(c6)).as("C6 status").isEqualTo(1);

        assertThat(stub.calls().stream().filter(c -> c.method().equals("DELETE")).toList()).as("nothing cancelled").isEmpty();
        assertThat(count("select count(*) from billing_exception where business_id in (?, ?, ?, ?)", c2.businessId(), c3.businessId(), c5.businessId(), c6.businessId()))
                .as("PO U4: never a second exception").isEqualTo(4);
    }

    @Test
    void aSubscriptionBackingAnEntitlementIsNeverCancelled() throws Exception {
        Tenant active = usTenant("r502-c4-active");
        Paid live = paid(active, checkout(active));
        outcomes.process(deliver(live.checkoutCompletedEvent()));
        Tenant t = usTenant("r502-c4");
        Paid p = paidAfterCancel(t);
        // the provider reports this payment's subscription as the one backing the other restaurant's entitlement
        ((Map<String, Object>) ((Map<String, Object>) p.invoice.get("parent")).get("subscription_details")).put("subscription", live.subscriptionRef);
        stub.on("GET", "/v1/invoices/" + p.invoiceRef, c -> StripeStub.ok(StripeFixtures.json(p.invoice)));
        stub.on("GET", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.json(StripeFixtures.list(List.of(p.session)))));
        p.session.put("subscription", live.subscriptionRef);
        stub.on("GET", "/v1/subscriptions/" + live.subscriptionRef, c -> StripeStub.ok(StripeFixtures.json(live.subscription)));
        stub.on("GET", "/v1/invoices", c -> StripeStub.ok(StripeFixtures.json(StripeFixtures.list(List.of()))));

        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(p)));

        assertThat(handedBack(t)).as("C4").isEqualTo(1);
        assertThat(stub.calls().stream().filter(c -> c.method().equals("DELETE")).toList()).isEmpty();
    }

    @Test
    void anUncertainCancellationIsRetriedByReFetchingAndHandedBackOnTheLastRun() throws Exception {
        Tenant t = usTenant("r502-uncertain");
        Paid p = paidAfterCancel(t);
        String pay = paymentId(p);
        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> {
            p.subscription.put("status", "canceled"); // Stripe did it, but the answer was lost
            return StripeStub.timeout();
        });
        assertThatThrownBy(() -> cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay))).isInstanceOf(TransientJobException.class);
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay));
        assertThat(audits("PROVIDER_SUBSCRIPTION_ALREADY_CANCELLED", p.subscriptionRef)).as("the retry re-fetched first").isEqualTo(1);
        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + p.subscriptionRef)).hasSize(1);

        Tenant last = usTenant("r502-last-run");
        Paid q = paidAfterCancel(last);
        stub.on("DELETE", "/v1/subscriptions/" + q.subscriptionRef, c -> StripeStub.timeout());
        jdbc.update("update job set claims = 8, first_attempt_at = now() where type = ? and key = ?", ProviderSubscriptionCancelHandler.TYPE, "payment:" + paymentId(q));
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(q)));
        assertThat(handedBack(last)).as("uncertain on the last run").isEqualTo(1);
    }

    /** T7: a lost DELETE answer while Stripe still shows the subscription active → the retry re-fetches and cancels again. */
    @Test
    void aCancellationTimeoutWithTheSubscriptionStillActiveIsCancelledOnTheRetry() throws Exception {
        Tenant t = usTenant("r502-still-active");
        Paid p = paidAfterCancel(t);
        String pay = paymentId(p);
        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> StripeStub.timeout()); // nothing happened at Stripe
        assertThatThrownBy(() -> cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay))).isInstanceOf(TransientJobException.class);
        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> {
            p.subscription.put("status", "canceled");
            return StripeStub.ok(StripeFixtures.json(p.subscription));
        });
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay));
        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + p.subscriptionRef)).hasSize(2);
        assertThat(audits("PROVIDER_SUBSCRIPTION_CANCELLED", p.subscriptionRef)).isEqualTo(1);
        assertThat(handedBack(t)).isZero();
    }

    /** T7: the provider no longer has the invoice → nothing to cancel safely; the existing exception goes to an operator. */
    @Test
    void aMissingProviderObjectHandsTheExceptionBack() throws Exception {
        Tenant t = usTenant("r502-missing");
        Paid p = paidAfterCancel(t);
        stub.on("GET", "/v1/invoices/" + p.invoiceRef, c -> StripeStub.error(404, "invalid_request_error", "resource_missing"));
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(paymentId(p)));
        assertThat(handedBack(t)).isEqualTo(1);
        assertThat(stub.calls().stream().filter(c -> c.method().equals("DELETE")).toList()).isEmpty();
        assertThat(count("select count(*) from billing_exception where business_id = ?", t.businessId())).isEqualTo(1);
    }

    @Test
    void providerCallsNeverRunInsideATransactionAndTheJobCountsAgainstStripe() throws Exception {
        Tenant t = usTenant("r502-guard");
        Paid p = paidAfterCancel(t);
        String pay = paymentId(p);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay))))
                .isInstanceOf(ProviderCallInTransactionException.class);
        assertThat(jdbc.queryForObject("select " + cancelHandler.providerSql() + " from job j where j.type = ? and j.key = ?", String.class,
                ProviderSubscriptionCancelHandler.TYPE, "payment:" + pay)).as("R4-16-D1 attribution").isEqualTo("STRIPE");
    }

    // ---- webhook intake ----

    @Test
    void anOversizeOrBadlySignedStripeWebhookIsRefusedAndNothingIsStored() throws Exception {
        String payload = StripeFixtures.event("invoice.paid", Map.of("id", "in_synthetic_bad", "object", "invoice"));
        String header = Webhook.Signature.generateSignatureHeader(payload, StripeStubBillingConfig.WEBHOOK_SECRET);
        int tampered = mockMvc.perform(post("/api/billing/webhooks/STRIPE").contentType(MediaType.APPLICATION_JSON).content(payload + " ").header("Stripe-Signature", header))
                .andReturn().getResponse().getStatus();
        assertThat(tampered).isEqualTo(400);
        byte[] big = new byte[WebhookController.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        int oversize = mockMvc.perform(post("/api/billing/webhooks/STRIPE").contentType(MediaType.APPLICATION_JSON).content(big).header("Stripe-Signature", header))
                .andReturn().getResponse().getStatus();
        assertThat(oversize).isEqualTo(413);
        assertThat(count("select count(*) from provider_event where provider = 'STRIPE'")).isZero();
    }
}
