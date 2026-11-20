package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.PaidActivation;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agafari.com.billing.stripe.RenewalFixtures.epoch;
import static com.agafari.com.billing.stripe.RenewalFixtures.raw;
import static com.agafari.com.billing.stripe.RenewalFixtures.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R5-03 on PostgreSQL: the authentic RF scenario (renewal fails, the subscription goes past_due, the owner's new card
 * pays the open invoice within grace) through the real webhook, inbox, outcome service, lifecycle, ledger and evidence
 * triggers, with every Stripe re-fetch answered by the {@link RenewalFixtures AUTHENTIC} objects (one SYNTHETIC
 * PaymentIntent, see {@link RenewalFixtures#recoveredPaymentIntent()}).
 *
 * <p>The starting PROVIDER row is <b>seeded</b> (SYNTHETIC ledger rows for the authentic first invoice and period), because the
 * RF subscription was created directly in the sandbox, not through an Agafari checkout. Ids are re-keyed per test.
 */
@Import(AuthenticStripeBillingConfig.class)
class AuthenticRenewalFlowTest extends AbstractIntegrationTest {

    private static final AtomicInteger RUN = new AtomicInteger();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StripeStub stub;
    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private WebhookController webhooks;
    @Autowired private MonetizationClock clock;
    @Autowired private MonetizationLocks locks;
    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper mapper;

    private final Map<String, String> rekey = new LinkedHashMap<>();
    private String priceId;
    private Instant ps, pe;

    @BeforeEach
    void setUp() {
        stub.reset();
        String suffix = "R" + RUN.incrementAndGet() + (System.nanoTime() % 100000);
        for (String id : new String[]{str("rf_invoice_recovered", "id"), str("rf_subscription_recovered", "id"), str("rf_subscription_recovered", "customer"),
                str("rf_subscription_recovered", "items.data[0].price.id"), str("rf_invoice_recovered", "payments.data[0].payment.payment_intent"),
                str("rf_subscription_create", "latest_invoice"), str("rf_event_invoice_paid", "id"), str("rf_event_payment_failed", "id"),
                str("rf_event_subscription_updated", "id")}) {
            rekey.put(id, id + suffix);
        }
        outcomes.setBillingEnabled(Market.US, true);
        webhooks.setBillingEnabled(Market.US, true);
        ps = epoch("rf_subscription_create", "items.data[0].current_period_start");
        pe = epoch("rf_subscription_create", "items.data[0].current_period_end");
        User admin = data.admin("r503-admin");
        Timestamp from = Timestamp.from(ps.minus(Duration.ofDays(1)));
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (1, 'SETTING_2', true, false, true, ?, ?, ?) on conflict do nothing", from, from, admin.getId());
        priceId = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'US', 'USD', 'MONTH', 2500, 1, ?, true, ?)", priceId, id(str("rf_subscription_recovered", "items.data[0].price.id")), from);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        outcomes.setBillingEnabled(Market.US, false);
        webhooks.setBillingEnabled(Market.US, false);
    }

    private String id(String authentic) {
        return rekey.getOrDefault(authentic, authentic);
    }

    private String fixture(String name) {
        String s = raw(name);
        for (Map.Entry<String, String> e : rekey.entrySet()) s = s.replace("\"" + e.getKey() + "\"", "\"" + e.getValue() + "\"");
        return s;
    }

    private void at(Instant simulated) {
        clock.setOffset(Duration.between(Instant.now(), simulated));
    }

    /** SYNTHETIC seed: the RF subscription's first period as an Agafari PROVIDER row, with verified ledger evidence. */
    private String seed(Tenant t) {
        jdbc.update("update business set country = 'US' where id = ?", t.businessId());
        at(ps.plus(Duration.ofHours(1)));
        String invoiceId = UUID.randomUUID().toString(), paymentId = UUID.randomUUID().toString(), first = id(str("rf_subscription_create", "latest_invoice"));
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            jdbc.update("insert into billing_account (id, business_id, market, provider, provider_customer_ref) values (?, ?, 'US', 'STRIPE', ?)", UUID.randomUUID().toString(),
                    t.businessId(), id(str("rf_subscription_recovered", "customer")));
            jdbc.update("insert into billing_invoice (id, business_id, price_id, period_start, period_end, amount_minor, currency, status, provider_invoice_ref) "
                    + "values (?, ?, ?, ?, ?, 2500, 'USD', 'PAID', ?)", invoiceId, t.businessId(), priceId, Timestamp.from(ps), Timestamp.from(pe), first);
            jdbc.update("insert into payment (id, business_id, invoice_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                    + "values (?, ?, ?, 'STRIPE', ?, 2500, 'USD', 'CHARGE', 'SUCCEEDED', ?, ?, 'SIGNED_WEBHOOK+API_FETCH')", paymentId, t.businessId(), invoiceId, first,
                    Timestamp.from(ps), Timestamp.from(clock.now()));
            lifecycle.activatePaid(t.businessId(), new PaidActivation("MODEL_4", 1, priceId, "STRIPE", id(str("rf_subscription_recovered", "id")), null, ps, pe,
                    com.agafari.com.billing.TestEvidence.ref(paymentId, invoiceId), clock.now()));
        });
        return jdbc.queryForObject("select id from business_subscription where business_id = ? and status <> 'EXPIRED'", String.class, t.businessId());
    }

    private String deliver(String eventFixture) throws Exception {
        return deliverPayload(fixture(eventFixture));
    }

    private String deliverPayload(String payload) throws Exception {
        String header = Webhook.Signature.generateSignatureHeader(payload, AuthenticStripeBillingConfig.WEBHOOK_SECRET, clock.now().getEpochSecond());
        int status = mockMvc.perform(post("/api/billing/webhooks/STRIPE").contentType(MediaType.APPLICATION_JSON).content(payload).header("Stripe-Signature", header))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return jdbc.queryForObject("select id from provider_event where provider = 'STRIPE' and event_id = ?", String.class, mapper.readTree(payload).path("id").asString());
    }

    private void serveFailed() {
        String in = id(str("rf_invoice_recovered", "id")), sub = id(str("rf_subscription_recovered", "id"));
        stub.on("GET", "/v1/invoices/" + in, c -> StripeStub.ok(fixture("rf_invoice_failed")));
        stub.on("GET", "/v1/subscriptions/" + sub, c -> StripeStub.ok(fixture("rf_subscription_past_due")));
    }

    private void serveRecovered() {
        String in = id(str("rf_invoice_recovered", "id")), sub = id(str("rf_subscription_recovered", "id"));
        String pi = id(str("rf_invoice_recovered", "payments.data[0].payment.payment_intent"));
        stub.on("GET", "/v1/invoices/" + in, c -> StripeStub.ok(fixture("rf_invoice_recovered")));
        stub.on("GET", "/v1/payment_intents/" + pi, c -> {
            String synthetic = RenewalFixtures.recoveredPaymentIntent();
            for (Map.Entry<String, String> e : rekey.entrySet()) synthetic = synthetic.replace("\"" + e.getKey() + "\"", "\"" + e.getValue() + "\"");
            return StripeStub.ok(synthetic);
        });
        stub.on("GET", "/v1/subscriptions/" + sub, c -> StripeStub.ok(fixture("rf_subscription_recovered")));
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from business_subscription where id = ?", id);
    }

    private String eventStatus(String id) {
        return jdbc.queryForObject("select status from provider_event where id = ?", String.class, id);
    }

    @Test
    void rfFailureThenRecoveryWithinGraceFromTheAuthenticCapture() throws Exception {
        Tenant t = data.tenant("r503-rf");
        String sub = seed(t);
        assertThat(row(sub)).containsEntry("grace_days", 7).containsEntry("status", "ACTIVE");

        // invoice.payment_failed: re-fetch shows the open, attempted renewal invoice → PAST_DUE from period_end
        serveFailed();
        at(pe.plus(Duration.ofHours(1)));
        String failed = deliver("rf_event_payment_failed");
        assertThat(outcomes.process(failed)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(row(sub)).containsEntry("status", "PAST_DUE");
        assertThat(((Timestamp) row(sub).get("past_due_since")).toInstant()).isEqualTo(pe);

        // customer.subscription.updated (past_due): a state trigger only; nothing more to change
        String updated = deliver("rf_event_subscription_updated");
        outcomes.process(updated);
        assertThat(eventStatus(updated)).isEqualTo("IGNORED");
        assertThat(row(sub)).containsEntry("status", "PAST_DUE");

        // invoice.paid after the new card paid the same invoice: recovery within grace
        serveRecovered();
        Instant paidAt = epoch("rf_invoice_recovered", "status_transitions.paid_at");
        at(paidAt.plusSeconds(60));
        String paid = deliver("rf_event_invoice_paid");
        assertThat(outcomes.process(paid)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        Map<String, Object> r = row(sub);
        assertThat(r).containsEntry("status", "ACTIVE").containsEntry("past_due_since", null);
        assertThat(((Timestamp) r.get("period_end")).toInstant()).isEqualTo(epoch("rf_invoice_recovered", "lines.data[0].period.end"));
        String in = id(str("rf_invoice_recovered", "id"));
        Map<String, Object> pay = jdbc.queryForMap("select * from payment where provider_payment_ref = ?", in);
        assertThat(pay).containsEntry("business_id", t.businessId()).containsEntry("checkout_session_id", null).containsEntry("amount_minor", 2500L);
        assertThat(((Timestamp) pay.get("occurred_at")).toInstant()).isEqualTo(paidAt);
        Map<String, Object> inv = jdbc.queryForMap("select * from billing_invoice where provider_invoice_ref = ?", in);
        assertThat(((Timestamp) inv.get("period_start")).toInstant()).isEqualTo(pe);
        assertThat(jdbc.queryForObject("select count(*) from subscription_period where subscription_id = ?", Integer.class, sub)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from billing_exception where business_id = ?", Integer.class, t.businessId())).isZero();

        // a failure event arriving after the payment (out of order): the re-fetch shows the invoice paid, so it is stale
        String eventId = id(str("rf_event_payment_failed", "id"));
        String stale = deliverPayload(fixture("rf_event_payment_failed").replace("\"" + eventId + "\"", "\"" + eventId + "late\"")); // SYNTHETIC event id
        outcomes.process(stale);
        assertThat(eventStatus(stale)).isEqualTo("IGNORED");
        assertThat(row(sub)).containsEntry("status", "ACTIVE");
    }
}
