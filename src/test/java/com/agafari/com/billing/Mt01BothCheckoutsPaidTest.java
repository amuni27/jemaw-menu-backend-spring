package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeStrategy;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R4-16, MT-01 (test strategy §4): an old {@code CANCEL_REQUESTED} checkout (A, Model 3) and its replacement (B,
 * Model 4) both receive successful payments. Built through the real checkout and replacement API, the signed webhook, the
 * inbox, reconciliation and the job runner, against the fake provider. Variants V1–V7; PD-9 is open, so V3/V4 assert the
 * first-verified-payment-wins outcome of the order that was forced.
 */
class Mt01BothCheckoutsPaidTest extends FinancialSuiteBase {

    private static final AtomicInteger KEYS = new AtomicInteger();

    private record Setup(Tenant t, String free, String a, String b, Instant tc, Instant tPayA, Instant tPayB, String refA, String refB) {
    }

    private enum Expect { A_AFTER_CANCEL, A_WINS, B_WINS, A_MANUAL }

    /** Free with Ads (OWNER) current; A opened through the API, then replaced by B (A → CANCEL_REQUESTED at t_c). */
    private Setup setup(String name, boolean aPaidAfterCancel) throws Exception {
        Tenant t = data.tenant("mt01-" + name);
        startOwnerFree(t);
        String free = current(t);
        FakeStrategy.nextKind = CheckoutKind.UPGRADE; // the fake replaces the current row; a real strategy decides this (R5, R6)
        assertThat(perform(t, "a-" + KEYS.incrementAndGet(), price3).andReturn().getResponse().getStatus()).isEqualTo(201);
        String a = jdbc.queryForObject("select id from checkout_session where business_id = ? and price_id = ?", String.class, t.businessId(), price3);
        int version = jdbc.queryForObject("select row_version from checkout_session where id = ?", Integer.class, a);
        String body = "{\"priceId\":\"" + price + "\",\"replaceCheckoutId\":\"" + a + "\",\"replaceRowVersion\":" + version + "}";
        assertThat(mockMvc.perform(post("/api/billing/checkouts").contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", "b-" + KEYS.incrementAndGet())
                .header("Authorization", data.bearer(t))).andReturn().getResponse().getStatus()).isEqualTo(201);
        String b = jdbc.queryForObject("select id from checkout_session where business_id = ? and price_id = ?", String.class, t.businessId(), price);
        Map<String, Object> rowA = jdbc.queryForMap("select status, cancel_requested_at, kind, replaces_subscription_id from checkout_session where id = ?", a);
        Map<String, Object> rowB = jdbc.queryForMap("select status, kind, replaces_subscription_id from checkout_session where id = ?", b);
        assertThat(rowA).containsEntry("status", "CANCEL_REQUESTED").containsEntry("kind", "UPGRADE").containsEntry("replaces_subscription_id", free);
        assertThat(rowB).containsEntry("status", "OPEN").containsEntry("kind", "UPGRADE").containsEntry("replaces_subscription_id", free);
        Instant tc = ((Timestamp) rowA.get("cancel_requested_at")).toInstant();
        Instant tPayA = aPaidAfterCancel ? tc.plusSeconds(1) : tc.minusSeconds(1);
        Instant tPayB = tc.plusSeconds(2);
        String refA = paid(a, tPayA), refB = paid(b, tPayB);
        // the provider lists both payments, so every reconciliation run sees them
        fakeEt.record(new ProviderRecord(refA, RecordType.PAYMENT, tPayA));
        fakeEt.record(new ProviderRecord(refB, RecordType.PAYMENT, tPayB));
        return new Setup(t, free, a, b, tc, tPayA, tPayB, refA, refB);
    }

    private String webhook(String ref) throws Exception {
        String eventId = "evt_" + ref;
        assertThat(deliverSigned(eventId, ref).getStatus()).isEqualTo(200);
        return eventRowId(eventId);
    }

    private String payment(String ref) {
        return jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref);
    }

    // ---- variants ----

    @Test
    void v1PaidAfterCancelAFirstThenB() throws Exception {
        Setup s = setup("v1", true);
        String ea = webhook(s.refA), eb = webhook(s.refB);
        forced(() -> process(ea), () -> process(eb));
        verify("V1", s, Expect.A_AFTER_CANCEL, "A,B");
    }

    @Test
    void v2PaidAfterCancelBFirstThenA() throws Exception {
        Setup s = setup("v2", true);
        String ea = webhook(s.refA), eb = webhook(s.refB);
        forced(() -> process(eb), () -> process(ea));
        verify("V2", s, Expect.A_AFTER_CANCEL, "B,A");
    }

    @Test
    void v3PaidBeforeCancelAFirstThenB() throws Exception {
        Setup s = setup("v3", false);
        String ea = webhook(s.refA), eb = webhook(s.refB);
        forced(() -> process(ea), () -> process(eb));
        verify("V3", s, Expect.A_WINS, "A,B");
    }

    @Test
    void v4PaidBeforeCancelBFirstThenA() throws Exception {
        Setup s = setup("v4", false);
        String ea = webhook(s.refA), eb = webhook(s.refB);
        forced(() -> process(eb), () -> process(ea));
        verify("V4", s, Expect.B_WINS, "B,A");
    }

    /** V5: webhooks dropped; reconciliation alone finds both payments (V1 and V3 timings, A listed first). */
    @Test
    void v5ReconciliationOnlyAfterCancel() throws Exception {
        Setup s = setup("v5a", true);
        reconcileOnly();
        verify("V5(V1)", s, Expect.A_AFTER_CANCEL, order(s));
    }

    @Test
    void v5ReconciliationOnlyBeforeCancel() throws Exception {
        Setup s = setup("v5b", false);
        reconcileOnly();
        assertThat(order(s)).as("reconciliation processes in listing order: A first").isEqualTo("A,B");
        verify("V5(V3)", s, Expect.A_WINS, "A,B");
    }

    private void reconcileOnly() {
        ReconciliationService.DailyResult r = reconciliation.daily(clock.now());
        assertThat(r.eventsCreated()).isEqualTo(2);
        drainInternalEvents();
    }

    /**
     * V6: webhook and reconciliation events for both payments, processed by two job runners on separate connection pools.
     * The configuration gate holds all four attempts until they are proven waiting together; the order is then whatever the
     * database grants, recorded and checked against the matching expectation.
     */
    @Test
    void v6WebhookAndReconciliationTwoWorkersAfterCancel() throws Exception {
        Setup s = setup("v6a", true);
        String order = twoWorkers(s, "MT-01 V6 (V2 timing)");
        verify("V6(V2)", s, Expect.A_AFTER_CANCEL, order);
    }

    @Test
    void v6WebhookAndReconciliationTwoWorkersBeforeCancel() throws Exception {
        Setup s = setup("v6b", false);
        String order = twoWorkers(s, "MT-01 V6 (V4 timing)");
        verify("V6(V4)", s, order.startsWith("A") ? Expect.A_WINS : Expect.B_WINS, order);
    }

    private String twoWorkers(Setup s, String race) throws Exception {
        webhook(s.refA);
        webhook(s.refB);
        inbox.storeInternal(PROVIDER, s.refA);
        inbox.storeInternal(PROVIDER, s.refB);
        // only the four inbox jobs may run in this window: other jobs (checkout expiry, creation backstop) wait
        jdbc.update("update job set run_at = now() + interval '1 day' where type <> 'inbox.process' and status = 'PENDING'");
        try (HikariDataSource p1 = pool(); HikariDataSource p2 = pool()) {
            JobRunner w1 = runner(new JdbcTemplate(p1), null), w2 = runner(new JdbcTemplate(p2), null);
            Gate gate = configGate();
            List<Future<?>> attempts = new ArrayList<>();
            Future<List<Future<?>>> polled1 = async(w1::pollOnce), polled2 = async(w2::pollOnce);
            attempts.addAll(join(polled1));
            attempts.addAll(join(polled2));
            assertThat(attempts).as("all four events claimed across the two runners").hasSize(4);
            gate.awaitBlocked(4, race);
            gate.close();
            for (Future<?> f : attempts) settle(f);
        }
        assertThat(count("select count(*) from job where type = 'inbox.process' and status <> 'DONE'")).isZero();
        return order(s);
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

    /** V7: V1 with {@code idempotentRefund = false} (the ET default): no automatic refund, a manual-refund review instead. */
    @Test
    void v7WithoutSafeRefundCapabilityAIsAManualReview() throws Exception {
        fakeEt.capabilities(new ProviderCapabilities(true, true, false, Duration.ofHours(24), true));
        Setup s = setup("v7", true);
        String ea = webhook(s.refA), eb = webhook(s.refB);
        forced(() -> process(ea), () -> process(eb));
        verify("V7", s, Expect.A_MANUAL, "A,B");
    }

    /** The order in which the two payments were recorded (first committed outcome first). */
    private String order(Setup s) {
        List<String> refs = jdbc.queryForList("select provider_payment_ref from payment where provider_payment_ref in (?, ?) order by verified_at, id", String.class, s.refA, s.refB);
        return String.join(",", refs.stream().map(r -> r.equals(s.refA) ? "A" : "B").toList());
    }

    // ---- the expected state (test strategy §4, every variant) ----

    private void verify(String variant, Setup s, Expect expect, String observedOrder) throws Exception {
        Tenant t = s.t;
        String accepted = switch (expect) {
            case A_WINS -> s.refA;
            default -> s.refB;
        };
        String other = accepted.equals(s.refA) ? s.refB : s.refA;

        // entitlement: one current row, the Free row ended CHANGED and replaced by it
        assertThat(currentRows(t)).as("%s: exactly one current subscription", variant).isEqualTo(1);
        String now = current(t);
        assertThat(subRow(s.free)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED").containsEntry("replaced_by_id", now);
        assertThat(subRow(now)).containsEntry("source", "PROVIDER").containsEntry("plan_code", accepted.equals(s.refA) ? "MODEL_3" : "MODEL_4");

        // paid periods: exactly one, referencing the accepted payment
        List<Map<String, Object>> periods = jdbc.queryForList("select subscription_id, payment_id from subscription_period where business_id = ?", t.businessId());
        assertThat(periods).as("%s: one paid period", variant).hasSize(1);
        assertThat(periods.get(0)).containsEntry("payment_id", payment(accepted)).containsEntry("subscription_id", now);

        // ledger: two verified charges
        assertThat(jdbc.queryForList("select status, kind, verified_at is not null as verified from payment where business_id = ?", t.businessId()))
                .as("%s: two verified charges", variant).hasSize(2).allSatisfy(p -> assertThat(p).containsEntry("status", "SUCCEEDED").containsEntry("kind", "CHARGE").containsEntry("verified", true));

        // matrix decision for the other charge
        Map<String, Object> ex = jdbc.queryForMap("select code, status, involves_customer_funds, refund_request_id from billing_exception where payment_id = ?", payment(other));
        assertThat(count("select count(*) from billing_exception where business_id = ?", t.businessId())).as("%s: one exception", variant).isEqualTo(1);
        switch (expect) {
            case A_AFTER_CANCEL -> {
                assertThat(ex).as("%s: row 4, automatic full refund", variant).containsEntry("code", PaymentOutcome.PAID_AFTER_CANCEL).containsEntry("status", "AUTO_HANDLING");
                Map<String, Object> refund = jdbc.queryForMap("select id, status, amount_minor from refund_request where payment_id = ?", payment(other));
                assertThat(refund.get("id")).isEqualTo(ex.get("refund_request_id"));
                assertThat(((Number) refund.get("amount_minor")).longValue()).as("a full refund").isEqualTo(1500L);
            }
            case A_WINS, B_WINS -> {
                assertThat(ex).as("%s: row 6, never an activation or a refund", variant).containsEntry("code", PaymentOutcome.STATE_CHANGED).containsEntry("status", "OPEN")
                        .containsEntry("refund_request_id", null);
                assertThat(refunds(t)).isZero();
            }
            case A_MANUAL -> {
                assertThat(ex).as("%s: no safe capability, a manual-refund review", variant).containsEntry("code", PaymentOutcome.REFUND_REQUIRED_MANUAL).containsEntry("status", "OPEN")
                        .containsEntry("refund_request_id", null);
                assertThat(refunds(t)).isZero();
            }
        }
        assertThat(ex).containsEntry("involves_customer_funds", true);

        // owner state while funds are under review (R-2)
        JsonNode view = planView(t);
        assertThat(view.path("paymentUnderReview").isMissingNode()).as("%s: payment under review shown", variant).isFalse();
        assertThat(httpStatusWithConflictCode(t, "r2-" + KEYS.incrementAndGet(), price, "PAYMENT_UNDER_REVIEW")).as("%s: new paid checkouts blocked (R-2)", variant).isEqualTo(409);

        // refund execution, where one was requested: submitted once, unsettled (settlement is BLOCKED)
        if (expect == Expect.A_AFTER_CANCEL) {
            JobRunner r = runner(null, null);
            drain(r, "refund.execute");
            assertThat(jdbc.queryForObject("select status from refund_request where payment_id = ?", String.class, payment(other))).as("%s: submitted, not settled", variant).isEqualTo("SUBMITTED");
            assertThat(fakeEt.refundsCreated()).isEqualTo(1);
        }

        // audit: one event per lifecycle change, exception, refund request and session transition
        assertThat(jdbc.queryForList("""
                select action, target_id, count(*) as n from admin_audit_event where target_id in (
                    select id from business_subscription where business_id = ? union select id from checkout_session where business_id = ?
                    union select id from billing_exception where business_id = ? union select id from payment where business_id = ?
                    union select provider_payment_ref from payment where business_id = ?
                    union select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?)
                group by action, target_id having count(*) > 1""", t.businessId(), t.businessId(), t.businessId(), t.businessId(), t.businessId(), t.businessId()))
                .as("%s: no duplicate audit event", variant).isEmpty();
        Map<String, Integer> audit = auditByAction(t);

        // reconciliation: clean, every charge durably accounted for, reviews and the unsettled refund still visible
        Map<String, Object> visible = assertReconciliationClean("MT-01 " + variant);

        // idempotency: re-deliver both webhooks (same and new event ids), re-run reconciliation
        Map<String, Object> state = businessState(t), books = bookkeeping();
        int alreadyStored = (eventRowId("evt_" + s.refA) == null ? 0 : 1) + (eventRowId("evt_" + s.refB) == null ? 0 : 1);
        assertThat(deliverSigned("evt_" + s.refA, s.refA).getStatus()).isEqualTo(200);
        assertThat(deliverSigned("evt_" + s.refB, s.refB).getStatus()).isEqualTo(200);
        assertThat(deliverSigned("evt_again_" + s.refA, s.refA).getStatus()).isEqualTo(200);
        assertThat(deliverSigned("evt_again_" + s.refB, s.refB).getStatus()).isEqualTo(200);
        process(eventRowId("evt_again_" + s.refA));
        process(eventRowId("evt_again_" + s.refB));
        for (String id : jdbc.queryForList("select id from provider_event where status = 'PENDING'", String.class)) process(id);
        reconciliation.daily(clock.now());
        drainInternalEvents();
        assertThat(businessState(t)).as("%s: redelivery and reconciliation change no business state or business audit", variant).isEqualTo(state);
        Map<String, Object> booksAfter = bookkeeping();

        evidence("mt01.txt", variant + " | forced/observed order=" + observedOrder + " | accepted=" + (accepted.equals(s.refA) ? "A" : "B") + " other=" + ex.get("code") + "/" + ex.get("status")
                + " | audit=" + audit + " | visible=" + visible);
        evidence("mt01.txt", variant + " | bookkeeping before redelivery=" + books + " after=" + booksAfter + " (expected: " + (4 - alreadyStored)
                + " new provider events with their jobs; an event id already stored is acknowledged, not stored again)");
        assertThat(((Number) booksAfter.get("providerEvents")).intValue() - ((Number) books.get("providerEvents")).intValue())
                .as("%s: only event ids not already stored were stored", variant).isEqualTo(4 - alreadyStored);
    }
}
