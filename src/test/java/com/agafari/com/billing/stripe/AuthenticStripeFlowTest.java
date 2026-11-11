package com.agafari.com.billing.stripe;

import com.agafari.com.billing.CheckoutService;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcome;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.ProviderSubscriptionCancelHandler;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeResponse;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.fixtureId;
import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.num;
import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF R5-02 Q6 guide §15 (F1–F4) on PostgreSQL: the real checkout API, webhook intake, inbox, outcome matrix, ledger and
 * evidence triggers, and the D4 cancellation job, driven by {@link AuthenticStripeFixtures AUTHENTIC} sandbox objects.
 *
 * <p>Each test runs "at" the captured times ({@link MonetizationClock} offset). Each re-keys the authentic set
 * ({@link AuthenticStripeFixtures.Rekey}: Stripe ids get a per-test suffix, and the disposable capture UUIDs become this
 * test's checkout and restaurant), so tests own their rows; every other byte, and so every relationship, is authentic.
 * Webhooks are signed with a synthetic secret. No network; no real Stripe account; no billing flag outside the test.
 */
@Import(AuthenticStripeBillingConfig.class)
class AuthenticStripeFlowTest extends AbstractIntegrationTest {

    private static final AtomicInteger RUN = new AtomicInteger();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StripeStub stub;
    @Autowired private CheckoutService checkouts;
    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private WebhookController webhooks;
    @Autowired private ProviderSubscriptionCancelHandler cancelHandler;
    @Autowired private MonetizationClock clock;
    @Autowired private ObjectMapper mapper;

    private AuthenticStripeFixtures.Rekey rk;
    private String priceId;

    @BeforeEach
    void setUp() {
        stub.reset();
        rk = new AuthenticStripeFixtures.Rekey("Q6t" + RUN.incrementAndGet() + Long.toString(System.nanoTime() % 100000));
        checkouts.setBillingEnabled(Market.US, true);
        outcomes.setBillingEnabled(Market.US, true);
        webhooks.setBillingEnabled(Market.US, true);
        User admin = data.admin("q6-admin");
        // configuration and price valid from a day before the authentic capture (Agafari stays authoritative, Q3)
        Timestamp from = Timestamp.from(Instant.ofEpochSecond(num("a_session_create", "created")).minus(Duration.ofDays(1)));
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (1, 'SETTING_2', true, false, true, ?, ?, ?)", from, from, admin.getId());
        priceId = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'US', 'USD', 'MONTH', 2500, 1, ?, true, ?)", priceId, rk.id(str("price_m4_month", "id")), from);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        checkouts.setBillingEnabled(Market.US, false);
        outcomes.setBillingEnabled(Market.US, false);
        webhooks.setBillingEnabled(Market.US, false);
    }

    // ---- helpers ----

    private void at(Instant simulated) {
        clock.setOffset(Duration.between(Instant.now(), simulated));
    }

    private static Instant epoch(String fixture, String path) {
        return Instant.ofEpochSecond(num(fixture, path));
    }

    private Tenant usTenant(String label) {
        Tenant t = data.tenant(label);
        jdbc.update("update business set country = 'US' where id = ?", t.businessId());
        return t;
    }

    private StripeResponse ok(String fixture) {
        return StripeStub.ok(rk.fixture(fixture));
    }

    /** Creates the Agafari checkout through the real API, answered by the authentic session; the capture's ids become this test's. */
    private String checkout(Tenant t, String p) throws Exception {
        rk.value(fixtureId(p.equals("a") ? "BIZ_A" : "BIZ_B"), t.businessId());
        at(epoch(p + "_session_create", "created").minusSeconds(10));
        stub.on("POST", "/v1/checkout/sessions", c -> {
            Map<?, ?> metadata = (Map<?, ?>) c.params().get("metadata");
            rk.value(fixtureId(p.equals("a") ? "CHK_A" : "CHK_B"), String.valueOf(metadata.get("agafari_checkout_id")));
            return ok(p + "_session_create");
        });
        int status = mockMvc.perform(post("/api/billing/checkouts").contentType(MediaType.APPLICATION_JSON).content("{\"priceId\":\"" + priceId + "\"}")
                .header("Idempotency-Key", "q6-" + UUID.randomUUID()).header("Authorization", data.bearer(t))).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(201);
        String id = jdbc.queryForObject("select id from checkout_session where business_id = ? and status = 'OPEN'", String.class, t.businessId());
        assertThat(jdbc.queryForObject("select provider_session_ref from checkout_session where id = ?", String.class, id)).isEqualTo(rk.id(str(p + "_session_create", "id")));
        return id;
    }

    /** The adapter's re-fetches of an initial payment, answered by the authentic (re-keyed) objects. */
    private void serveInitial(String p) {
        String in = rk.id(str(p + "_session_after", "invoice")), sub = rk.id(str(p + "_session_after", "subscription"));
        stub.on("GET", "/v1/invoices/" + in, c -> ok(c.params().containsKey("expand") ? p + "_invoice_verify" : p + "_invoice_plain"));
        stub.on("GET", "/v1/payment_intents/" + rk.id(str(p + "_payment_intent", "id")), c -> ok(p + "_payment_intent"));
        stub.on("GET", "/v1/subscriptions/" + sub, c -> ok(p + "_subscription"));
        stub.on("GET", "/v1/checkout/sessions", c -> sub.equals(c.params().get("subscription")) ? ok(p + "_sessions_by_sub") : ok("r_sessions_by_sub"));
        stub.on("GET", "/v1/invoices", c -> ok(p + "_open_invoices_none"));
    }

    /** Delivers an authentic (re-keyed) event through the real webhook, signed now in simulated time (synthetic secret). */
    private String deliver(String eventFixture) throws Exception {
        String payload = rk.fixture(eventFixture);
        String header = Webhook.Signature.generateSignatureHeader(payload, AuthenticStripeBillingConfig.WEBHOOK_SECRET, clock.now().getEpochSecond());
        int status = mockMvc.perform(post("/api/billing/webhooks/STRIPE").contentType(MediaType.APPLICATION_JSON).content(payload).header("Stripe-Signature", header))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return jdbc.queryForObject("select id from provider_event where provider = 'STRIPE' and event_id = ?", String.class, mapper.readTree(payload).path("id").asString());
    }

    private void afterPayment(String p) {
        at(epoch(p + "_invoice_verify", "status_transitions.paid_at").plusSeconds(60));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void assertActivatedFromAuthentic(Tenant t, String p) {
        Map<String, Object> pay = jdbc.queryForMap("select * from payment where business_id = ?", t.businessId());
        assertThat(pay).containsEntry("provider", "STRIPE").containsEntry("provider_payment_ref", rk.id(str(p + "_invoice_verify", "id")))
                .containsEntry("kind", "CHARGE").containsEntry("status", "SUCCEEDED");
        assertThat(((Timestamp) pay.get("occurred_at")).toInstant()).isEqualTo(epoch(p + "_invoice_verify", "status_transitions.paid_at"));
        Map<String, Object> inv = jdbc.queryForMap("select * from billing_invoice where business_id = ?", t.businessId());
        assertThat(inv).containsEntry("provider_invoice_ref", rk.id(str(p + "_invoice_verify", "id"))).containsEntry("amount_minor", 2500L).containsEntry("currency", "USD");
        assertThat(((Timestamp) inv.get("period_start")).toInstant()).as("Q2: the authentic line period").isEqualTo(epoch(p + "_invoice_verify", "lines.data[0].period.start"));
        assertThat(((Timestamp) inv.get("period_end")).toInstant()).isEqualTo(epoch(p + "_invoice_verify", "lines.data[0].period.end"));
        Map<String, Object> sub = jdbc.queryForMap("select * from business_subscription where business_id = ? and status <> 'EXPIRED'", t.businessId());
        assertThat(sub).containsEntry("source", "PROVIDER").containsEntry("provider", "STRIPE").containsEntry("plan_code", "MODEL_4")
                .containsEntry("provider_subscription_ref", rk.id(str(p + "_subscription", "id")));
        assertThat(jdbc.queryForObject("select provider_customer_ref from billing_account where business_id = ? and provider = 'STRIPE'", String.class, t.businessId()))
                .as("U5: stored once").isEqualTo(rk.id(str(p + "_subscription", "customer")));
        assertThat(count("select count(*) from billing_exception where business_id = ?", t.businessId())).isZero();
        assertThat(count("select count(*) from payment where business_id = ?", t.businessId())).isEqualTo(1);
    }

    // ---- F1: activation from authentic objects, either event order ----

    @Test
    void f1_authenticSessionEventFirstActivatesOnceWithTheAuthenticPeriod() throws Exception {
        Tenant t = usTenant("q6-f1-session-first");
        checkout(t, "a");
        serveInitial("a");
        afterPayment("a");
        assertThat(outcomes.process(deliver("a_event_session_completed"))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertActivatedFromAuthentic(t, "a");
        assertThat(outcomes.process(deliver("a_event_invoice_paid"))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertActivatedFromAuthentic(t, "a");
        assertThat(count("select count(*) from subscription_period where business_id = ?", t.businessId())).isEqualTo(1);
    }

    @Test
    void f1_authenticInvoiceEventFirstActivatesOnceToo() throws Exception {
        Tenant t = usTenant("q6-f1-invoice-first");
        checkout(t, "a");
        serveInitial("a");
        afterPayment("a");
        outcomes.process(deliver("a_event_invoice_paid"));
        outcomes.process(deliver("a_event_session_completed"));
        assertActivatedFromAuthentic(t, "a");
    }

    // ---- F2: the authentic 3DS payment, duplicate and concurrent delivery ----

    @Test
    void f2_theAuthentic3dsPaymentDeliveredTwiceAndConcurrentlyHasOneFinancialEffect() throws Exception {
        Tenant t = usTenant("q6-f2-3ds");
        checkout(t, "b");
        serveInitial("b");
        afterPayment("b");
        String a = deliver("b_event_session_completed");
        assertThat(deliver("b_event_session_completed")).as("the same event id is stored once").isEqualTo(a);
        String b = deliver("b_event_invoice_paid");
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
        assertActivatedFromAuthentic(t, "b");
        assertThat(count("select count(*) from subscription_period where business_id = ?", t.businessId())).isEqualTo(1);
    }

    // ---- F3: the authentic renewal is quarantined with no financial effect ----

    @Test
    void f3_theAuthenticRenewalEventIsQuarantinedWithoutAnyFinancialEffect() throws Exception {
        String cycle = rk.id(str("r_invoice_cycle_verify", "id"));
        stub.on("GET", "/v1/invoices/" + cycle, c -> ok("r_invoice_cycle_verify"));
        at(epoch("r_invoice_cycle_verify", "status_transitions.paid_at").plusSeconds(60));
        String event = deliver("r_event_invoice_paid_cycle");
        assertThat(outcomes.process(event)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat(count("select count(*) from payment where provider_payment_ref = ?", cycle)).isZero();
        assertThat(count("select count(*) from billing_exception where provider_event_id = ?", event)).isZero();
        assertThat(count("select count(*) from refund_request")).isZero();
        assertThat(count("select count(*) from job where type = ?", ProviderSubscriptionCancelHandler.TYPE)).isZero();
        assertThat(stub.calls("GET", "/v1/checkout/sessions")).as("a renewal is never correlated").isEmpty();
    }

    // ---- F4: paid after cancel (row 4) → the D4 job with the authentic re-fetch and cancel ----

    private String paidAfterCancel(Tenant t) throws Exception {
        String c = checkout(t, "a");
        at(epoch("a_session_create", "created").plusSeconds(5));
        mockMvc.perform(post("/api/billing/checkouts/{id}/cancel", c).header("Authorization", data.bearer(t))).andReturn();
        assertThat(((Timestamp) jdbc.queryForObject("select cancel_requested_at from checkout_session where id = ?", Timestamp.class, c)).toInstant())
                .isBefore(epoch("a_invoice_verify", "status_transitions.paid_at"));
        serveInitial("a");
        afterPayment("a");
        outcomes.process(deliver("a_event_session_completed"));
        return jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, rk.id(str("a_invoice_verify", "id")));
    }

    private Map<String, Object> exceptionOf(Tenant t) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_exception where business_id = ?", t.businessId());
        assertThat(rows).as("exactly one exception").hasSize(1);
        return rows.get(0);
    }

    private int handedBack(Tenant t) {
        return count("select count(*) from admin_audit_event where action in ('PROVIDER_SUBSCRIPTION_HANDED_BACK', 'BILLING_EXCEPTION_HANDED_BACK') and target_id = ?",
                exceptionOf(t).get("id"));
    }

    private static StripeResponse authenticError(int status, String fixture, AuthenticStripeFixtures.Rekey rk) {
        return new StripeResponse(status, HttpHeaders.of(Map.of("request-id", List.of("req_q6"))), rk.fixture(fixture));
    }

    @Test
    void f4_row4EnqueuesTheJobWhichCancelsTheAuthenticSubscriptionOnceWithNoRefund() throws Exception {
        Tenant t = usTenant("q6-f4-cancel");
        String pay = paidAfterCancel(t);
        Map<String, Object> ex = exceptionOf(t);
        assertThat(ex).containsEntry("code", PaymentOutcome.REFUND_REQUIRED_MANUAL).containsEntry("status", "OPEN");
        assertThat(ex.get("facts").toString()).contains("\"row\": 4");
        assertThat(count("select count(*) from job where type = ? and key = ?", ProviderSubscriptionCancelHandler.TYPE, "payment:" + pay)).isEqualTo(1);
        String sub = rk.id(str("a_subscription", "id"));
        List<String> subState = new ArrayList<>(List.of("a_subscription"));
        stub.on("GET", "/v1/subscriptions/" + sub, c -> ok(subState.get(0)));
        stub.on("DELETE", "/v1/subscriptions/" + sub, c -> {
            if (subState.get(0).equals("c_subscription_after")) return authenticError(404, "c_cancel_again", rk);
            subState.set(0, "c_subscription_after");
            return ok("c_cancel");
        });

        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay));

        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + sub)).hasSize(1)
                .allSatisfy(c -> assertThat(c.params()).containsEntry("invoice_now", false).containsEntry("prorate", false));
        assertThat(count("select count(*) from admin_audit_event where action = 'PROVIDER_SUBSCRIPTION_CANCELLED' and target_id = ?", sub)).isEqualTo(1);
        assertThat(exceptionOf(t)).as("code, status and facts unchanged").containsEntry("code", ex.get("code")).containsEntry("status", "OPEN").containsEntry("facts", ex.get("facts"));
        assertThat(count("select count(*) from refund_request")).as("no refund outside the matrix").isZero();

        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay)); // a duplicate run re-fetches the authentic canceled state
        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + sub)).as("no second cancellation").hasSize(1);
        assertThat(count("select count(*) from admin_audit_event where action = 'PROVIDER_SUBSCRIPTION_ALREADY_CANCELLED' and target_id = ?", sub)).isEqualTo(1);
    }

    @Test
    void f4_theAuthenticOtherOpenInvoiceHandsTheExceptionBackWithoutCancelling() throws Exception {
        Tenant t = usTenant("q6-f4-c5");
        String pay = paidAfterCancel(t);
        stub.on("GET", "/v1/invoices", c -> ok("o_open_invoices_other"));
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay));
        assertThat(handedBack(t)).as("C5").isEqualTo(1);
        assertThat(stub.calls().stream().filter(c -> c.method().equals("DELETE")).toList()).isEmpty();
    }

    // ---- tenant isolation (U3, C2) ----

    @Test
    void tenant_anAuthenticPaymentWhoseCheckoutBelongsToAnotherRestaurantIsQuarantinedForBoth() throws Exception {
        Tenant owner = usTenant("q6-t-owner");
        Tenant other = usTenant("q6-t-other");
        checkout(owner, "a");
        rk.value(fixtureId("BIZ_A"), other.businessId()); // Stripe's record names another restaurant for this checkout
        serveInitial("a");
        afterPayment("a");
        assertThat(outcomes.process(deliver("a_event_session_completed"))).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        for (Tenant x : List.of(owner, other)) {
            assertThat(count("select count(*) from payment where business_id = ?", x.businessId())).as("no ledger row for either").isZero();
            assertThat(count("select count(*) from business_subscription where business_id = ? and source = 'PROVIDER'", x.businessId())).isZero();
            assertThat(count("select count(*) from billing_exception where business_id = ?", x.businessId())).as("no ownership invented").isZero();
        }
    }

    @Test
    void tenant_theCancelJobHandsBackWhenStripesCheckoutRecordNamesAnotherCheckout() throws Exception {
        Tenant t = usTenant("q6-t-c2");
        String pay = paidAfterCancel(t);
        AuthenticStripeFixtures.Rekey foreign = new AuthenticStripeFixtures.Rekey(rk.id("").substring(0))
                .value(fixtureId("BIZ_A"), t.businessId()).value(fixtureId("CHK_A"), UUID.randomUUID().toString());
        stub.on("GET", "/v1/checkout/sessions", c -> StripeStub.ok(foreign.fixture("a_sessions_by_sub")));
        cancelHandler.handle(new ProviderSubscriptionCancelHandler.Payload(pay));
        assertThat(handedBack(t)).as("C2: ownership not proven").isEqualTo(1);
        assertThat(stub.calls().stream().filter(c -> c.method().equals("DELETE")).toList()).isEmpty();
    }
}
