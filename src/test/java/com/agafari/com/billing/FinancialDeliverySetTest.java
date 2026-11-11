package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.dto.request.BillingExceptionActionRequest;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AGF SUB-R4-16, the duplicate, replay, forged and out-of-order set (assessment §4.3), through the full path: signed HTTP
 * webhook, inbox, a real job runner, the matrix and the ledger. Expected: one effect or none, never two. Business state and
 * business audit are compared; delivery and job bookkeeping are reported separately (requirement 5).
 */
class FinancialDeliverySetTest extends FinancialSuiteBase {

    @Autowired private ExceptionService exceptionService;

    private JobRunner runner;

    private JobRunner runner() {
        if (runner == null) runner = runner(null, null);
        return runner;
    }

    private void runJobs() throws Exception {
        drain(runner(), "inbox.process", "refund.execute");
    }

    private void ledger(Tenant t, int payments, int periods, int exceptions) {
        assertThat(payments(t)).as("payments").isEqualTo(payments);
        assertThat(periods(t)).as("periods").isEqualTo(periods);
        assertThat(exceptions(t)).as("exceptions").isEqualTo(exceptions);
    }

    private void record(String caseName, String result) {
        evidence("delivery-set.txt", caseName + " | " + result);
    }

    // ---- duplicates ----

    @Test
    void theSameEventTwiceIsStoredOnceAndHasOneEffect() throws Exception {
        Tenant t = data.tenant("d-twice");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        assertThat(deliverSigned("evt_twice", ref).getStatus()).isEqualTo(200);
        assertThat(deliverSigned("evt_twice", ref).getStatus()).isEqualTo(200);
        assertThat(count("select count(*) from provider_event where event_id = 'evt_twice'")).isEqualTo(1);
        assertThat(count("select count(*) from job where type = 'inbox.process'")).isEqualTo(1);
        runJobs();
        ledger(t, 1, 1, 0);
        record("PAY-11 same event twice", "1 event, 1 job, 1 payment, 1 period");
    }

    @Test
    void theSameEventDeliveredConcurrentlyIsStoredOnce() throws Exception {
        Tenant t = data.tenant("d-concurrent");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        List<Integer> statuses = race(6, i -> () -> deliverSigned("evt_concurrent", ref).getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(count("select count(*) from provider_event where event_id = 'evt_concurrent'")).isEqualTo(1);
        assertThat(count("select count(*) from job where type = 'inbox.process'")).isEqualTo(1);
        runJobs();
        ledger(t, 1, 1, 0);
        record("PAY-11/SEC-04 same event x6 concurrently", "statuses=" + statuses + ", 1 event, 1 job, 1 payment");
    }

    @Test
    void twoEventsForOnePaymentHaveOneEffect() throws Exception {
        Tenant t = data.tenant("d-two-events");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_one", ref);
        deliverSigned("evt_other", ref);
        runJobs();
        ledger(t, 1, 1, 0);
        assertThat(count("select count(*) from provider_event where event_id in ('evt_one', 'evt_other') and status = 'PROCESSED'")).isEqualTo(2);
        record("SEC-07 two events, one payment", "2 events processed, 1 payment, 1 period");
    }

    // ---- replays ----

    @Test
    void aReplayAfterProcessingChangesNoBusinessStateOrAudit() throws Exception {
        Tenant t = data.tenant("d-replay");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_first", ref);
        runJobs();
        Map<String, Object> state = businessState(t), books = bookkeeping();

        deliverSigned("evt_first", ref); // the provider redelivers the same event
        deliverSigned("evt_replayed", ref); // or a new event for the same payment
        runJobs();

        assertThat(businessState(t)).isEqualTo(state);
        Map<String, Object> after = bookkeeping();
        assertThat((Integer) after.get("providerEvents") - (Integer) books.get("providerEvents")).as("only the new event id is stored").isEqualTo(1);
        record("SEC-04 replay after processing", "business state and audit unchanged; bookkeeping: +1 provider event (new id) and its DONE job; same id acknowledged only");
    }

    @Test
    void anAdminReplayOfAReviewedPaymentChangesNoFinancialState() throws Exception {
        Tenant t = data.tenant("d-admin-replay");
        startOwnerFree(t);
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_row6", ref);
        runJobs();
        String ex = jdbc.queryForObject("select id from billing_exception where business_id = ?", String.class, t.businessId());
        Map<String, Object> state = businessState(t);
        int version = jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, ex);

        exceptionService.replay(ex, new BillingExceptionActionRequest(version, null, null, null, null), admin.getId(), admin.getEmail());

        Map<String, Object> after = businessState(t);
        for (String key : List.of("subscriptions", "payments", "invoices", "periods", "refunds", "changes", "checkouts")) {
            assertThat(after.get(key)).as("financial state: %s", key).isEqualTo(state.get(key));
        }
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_REPLAYED'", ex)).as("the admin action is itself audited").isEqualTo(1);
        record("admin replay of a row-6 review", "financial state unchanged; one BILLING_EXCEPTION_REPLAYED audit (an admin action, not a redelivery)");
    }

    // ---- forgeries ----

    @Test
    void forgedOrUnsignedWebhooksAreRefusedAndStoreNothing() throws Exception {
        Tenant t = data.tenant("d-forged");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        byte[] body = webhookBody("evt_forged", ref);
        Map<String, Integer> statuses = new HashMap<>();
        statuses.put("bad signature", deliver(body, "forged").getStatus());
        statuses.put("no signature", deliver(body, null).getStatus());
        statuses.put("signature of another body", deliver(body, FakeProviderClient.sign(webhookBody("evt_other", ref))).getStatus());
        statuses.put("another provider's secret", deliver(body, FakeProviderClient.sign(body, "another-provider-secret")).getStatus());
        for (String reserved : List.of("{\"id\":\"" + InboxService.INTERNAL_PREFIX + ref + "\",\"type\":\"payment.succeeded\",\"payment_ref\":\"" + ref + "\"}",
                "{\"id\":\"evt_internal\",\"type\":\"" + InboxService.INTERNAL_TYPE + "\",\"payment_ref\":\"" + ref + "\"}")) {
            byte[] b = reserved.getBytes(StandardCharsets.UTF_8);
            statuses.put("reserved identity " + statuses.size(), deliver(b, FakeProviderClient.sign(b)).getStatus());
        }
        assertThat(statuses.values()).containsOnly(400);
        assertThat(count("select count(*) from provider_event")).isZero();
        runJobs();
        ledger(t, 0, 0, 0);
        assertThat(current(t)).isNull();
        record("PAY-13/SEC-04 forged or unsigned", statuses + "; nothing stored, nothing activated");
    }

    /** A correctly signed body naming another restaurant's payment: the re-fetch decides; only the true owner is affected, once. */
    @Test
    void aSignedBodyNamingAnotherRestaurantsPaymentOnlyTriggersARefetch() throws Exception {
        Tenant victim = data.tenant("d-victim"), attacker = data.tenant("d-attacker");
        String ref = paid(checkout(victim, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_by_attacker", ref);
        runJobs();
        ledger(victim, 1, 1, 0);
        ledger(attacker, 0, 0, 0);
        assertThat(current(attacker)).isNull();
        record("PAY-16 signed body naming another payment", "re-fetch decided: the real owner activated once; the other restaurant untouched");
    }

    @Test
    void amountCurrencyOrRestaurantMismatchesNeverActivate() throws Exception {
        Tenant amount = data.tenant("d-amount"), currency = data.tenant("d-currency"), foreign = data.tenant("d-foreign"), other = data.tenant("d-other");
        String ca = checkout(amount, CheckoutKind.NEW, null);
        String ra = paid(ca, soon(), new Money(ET_AMOUNT - 1, Currency.ETB), metadata(ca));
        String cc = checkout(currency, CheckoutKind.NEW, null);
        String rc = paid(cc, soon(), new Money(ET_AMOUNT, Currency.USD), metadata(cc));
        String cf = checkout(foreign, CheckoutKind.NEW, null);
        Map<String, String> wrong = new HashMap<>(metadata(cf));
        wrong.put(PaymentMetadata.BUSINESS_ID, other.businessId());
        String rf = paid(cf, soon(), new Money(ET_AMOUNT, Currency.ETB), wrong);
        deliverSigned("evt_amount", ra);
        deliverSigned("evt_currency", rc);
        deliverSigned("evt_foreign", rf);
        runJobs();

        assertThat(current(amount)).isNull();
        assertThat(onlyException(amount)).containsEntry("code", PaymentOutcome.VERIFICATION_FAILED);
        assertThat(current(currency)).isNull();
        assertThat(onlyException(currency)).containsEntry("code", PaymentOutcome.VERIFICATION_FAILED);
        assertThat(current(foreign)).isNull();
        assertThat(current(other)).isNull();
        ledger(foreign, 0, 0, 0);
        ledger(other, 0, 0, 0);
        assertThat(jdbc.queryForObject("select status from provider_event where event_id = 'evt_foreign'", String.class)).as("quarantined").isNotEqualTo("PENDING");
        record("PAY-14/15/16 mismatches", "amount: row 0, currency: row 0, foreign restaurant: quarantined; nothing activated");
    }

    // ---- out of order ----

    /** PAY-09 and PAY-24 (fake): pending answers first; activation only after the final verified success. */
    @Test
    void aPendingPaymentActivatesOnlyAfterItsFinalSuccess() throws Exception {
        fakeEt.script(FakeProviderClient.Script.DELAY).delayedFetches(2);
        Tenant t = data.tenant("d-pending");
        String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
        deliverSigned("evt_pending", ref);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            drain(runner(), "inbox.process");
            seen.add(payments(t) + "/" + jdbc.queryForObject("select status from provider_event where event_id = 'evt_pending'", String.class));
            if (i < 2) {
                ledger(t, 0, 0, 0);
                assertThat(current(t)).as("nothing while the provider says pending").isNull();
                jdbc.update("update job set run_at = now() - interval '1 second' where type = 'inbox.process' and status = 'PENDING'"); // the backoff passes (E3)
            }
        }
        ledger(t, 1, 1, 0);
        assertThat(providerSubscriptions(t)).isEqualTo(1);
        record("PAY-09/PAY-24 pending then success (fake; Stripe 3DS BLOCKED)", "per attempt payments/event=" + seen);
    }

    @Test
    void twoPaymentsForOneCheckoutInEitherOrderActivateOnceAndAccountForTheOther() throws Exception {
        Map<String, String> result = new HashMap<>();
        for (boolean reversed : List.of(false, true)) {
            Tenant t = data.tenant("d-order-" + reversed);
            String c = checkout(t, CheckoutKind.NEW, null);
            String first = paid(c, soon()), second = paid(c, soon().plusSeconds(1));
            deliverSigned("evt_a_" + reversed, reversed ? second : first);
            runJobs();
            deliverSigned("evt_b_" + reversed, reversed ? first : second);
            runJobs();
            ledger(t, 2, 1, 1);
            assertThat(onlyException(t)).containsEntry("code", PaymentOutcome.DUPLICATE_PAYMENT);
            assertThat(refunds(t)).isEqualTo(1);
            result.put(reversed ? "later payment first" : "earlier payment first", "1 activation, the other DUPLICATE_PAYMENT with a refund request");
        }
        assertLedgerAccounted("out-of-order payments");
        record("PAY-12/PAY-18 two payments, either order", result.toString());
    }

    /** PAY-08, NC-04: a browser return carrying a payment reference activates nothing; only the verified event does. */
    @Test
    void aBrowserReturnNeverActivatesAndTheVerifiedEventDoes() throws Exception {
        Tenant t = data.tenant("d-return");
        String c = checkout(t, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        int returned = mockMvc.perform(get("/api/billing/checkouts/" + c).param("payment_ref", ref).header("Authorization", data.bearer(t))).andReturn().getResponse().getStatus();
        assertThat(returned).isEqualTo(200);
        ledger(t, 0, 0, 0);
        assertThat(current(t)).as("NC-04").isNull();
        deliverSigned("evt_after_return", ref);
        runJobs();
        assertThat(providerSubscriptions(t)).isEqualTo(1);
        record("PAY-08 browser return without an event", "no activation until the verified event");
    }

    /** PAY-17: the owner clicks Pay several times at once: one checkout (one-active index, NC-07). */
    @Test
    void manyPayClicksAtOnceOpenOneCheckout() throws Exception {
        Tenant t = data.tenant("d-clicks");
        List<Integer> statuses = race(6, i -> () -> httpStatus(t, "click-" + i, price));
        assertThat(count("select count(*) from checkout_session where business_id = ? and status in ('CREATING', 'OPEN')", t.businessId())).isEqualTo(1);
        assertThat(statuses).doesNotContain(500);
        record("PAY-17 six Pay clicks at once", "statuses=" + statuses + "; one active checkout");
    }

    /** PAY-19 (PD-2): a quote is honored until its checkout expires, even after a new price version. */
    @Test
    void aPriceChangeDuringCheckoutHonorsTheQuote() throws Exception {
        Tenant t = data.tenant("d-quote");
        assertThat(httpStatus(t, "quote", price)).isEqualTo(201);
        String c = jdbc.queryForObject("select id from checkout_session where business_id = ?", String.class, t.businessId());
        String newer = price("MODEL_4", "ET", "ETB", ET_AMOUNT + 500); // prices are append-only: a change is a new version
        String ref = paid(c, soon());
        deliverSigned("evt_quote", ref);
        runJobs();
        assertThat(subRow(current(t))).containsEntry("price_id", price);
        assertThat(jdbc.queryForObject("select amount_minor from billing_invoice where business_id = ?", Long.class, t.businessId())).isEqualTo(ET_AMOUNT);
        assertThat(newer).isNotEqualTo(price);
        record("PAY-19 price change during checkout (PD-2)", "the quoted price and amount were honored");
    }

    @Test
    void aDeclinedPaymentChangesNothing() throws Exception {
        Tenant t = data.tenant("d-declined");
        String c = checkout(t, CheckoutKind.NEW, null);
        String ref = "pay_declined_" + c.substring(0, 8);
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.FAILED, new Money(ET_AMOUNT, Currency.ETB), null, metadata(c)));
        deliverSigned("evt_declined", ref);
        runJobs();
        ledger(t, 0, 0, 0);
        assertThat(checkoutStatus(c)).isEqualTo("OPEN");
        record("declined payment", "nothing recorded; the checkout stays open for a retry (PAY-06)");
    }
}
