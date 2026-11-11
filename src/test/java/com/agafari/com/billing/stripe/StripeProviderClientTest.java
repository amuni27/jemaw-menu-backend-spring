package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Currency;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.PaymentMetadata;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.billing.port.ExpireResult;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSession;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.SubscriptionCancelResult;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.stripe.StripeFixtures.Paid;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R5-02: the Stripe adapter against the real SDK with a stubbed HTTP layer and <b>synthetic</b> fixtures
 * ({@link StripeFixtures#SYNTHETIC}): no network call. Covers session creation and expiry, webhook signatures, the U1
 * qualifying-evidence rules, D6 first-invoice correlation, and the D4 re-fetch and cancellation.
 */
class StripeProviderClientTest {

    private static final String KEY = "sk_" + "test_" + "SyntheticR502Unit000000000001";
    private static final String WEBHOOK = "whsec_" + "SyntheticR502Unit000000000002";
    private static final String PREVIOUS = "whsec_" + "SyntheticR502Unit000000000003";
    private static final String ORIGIN = "https://app.example.test";

    private final StripeStub stub = new StripeStub();
    private StripeProviderClient client;

    private static StripeCredentials credentials(String previous) {
        Map<String, String> env = new java.util.HashMap<>(Map.of(StripeCredentials.ENV_SECRET_KEY, KEY, StripeCredentials.ENV_WEBHOOK_SECRET, WEBHOOK));
        if (previous != null) env.put(StripeCredentials.ENV_WEBHOOK_SECRET_PREVIOUS, previous);
        return StripeCredentials.fromEnvironment(env::get);
    }

    private StripeProviderClient client(StripeSettings settings) {
        return new StripeProviderClient(credentials(PREVIOUS), settings, stub, Clock.systemUTC());
    }

    @BeforeEach
    void setUp() {
        stub.reset();
        client = client(new StripeSettings(ORIGIN + "/billing/return", ORIGIN + "/billing/cancelled", ORIGIN));
    }

    private Paid paid() {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Paid p = new Paid("chk-1", "biz-1", StripeFixtures.id("cs"), "price_synthetic_m4", 2500, start, start.plusSeconds(20));
        StripeFixtures.register(stub, p);
        return p;
    }

    private VerifiedPayment verify(Paid p) {
        return client.verifyPayment(p.invoiceRef);
    }

    private static Map<String, String> headers(String name, String value) {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (value != null) h.put(name, value);
        return h;
    }

    // ---- capabilities (I1) ----

    @Test
    void capabilitiesAreOnlyTheDocumentedOnes() {
        assertThat(client.capabilities()).isEqualTo(new ProviderCapabilities(true, true, false, Duration.ofHours(24), false, true));
        assertThat(StripeProviderClient.apiVersion()).isEqualTo("2026-10-28.endive");
    }

    // ---- checkout session creation ----

    private ProviderSessionRequest request(Instant expiresAt) {
        return new ProviderSessionRequest("chk-1", "biz-1", new Money(2500, Currency.USD), expiresAt, PriceInterval.MONTH, "price_synthetic_m4", null,
                Map.of(PaymentMetadata.CHECKOUT_ID, "chk-1", PaymentMetadata.BUSINESS_ID, "biz-1"));
    }

    @Test
    void aSessionIsCreatedForOneCardPaidPriceWithTaxOffAndNoCheckoutIdOnTheSubscription() {
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.createdSession("cs_synthetic_new")));
        Instant expires = Instant.now().plus(23, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        ProviderSession session = client.createSession(request(expires), "checkout:chk-1");

        assertThat(session.providerSessionRef()).isEqualTo("cs_synthetic_new");
        assertThat(session.url()).isEqualTo("https://checkout.stripe.test/c/pay/cs_synthetic_new");
        StripeStub.Call call = stub.calls("POST", "/v1/checkout/sessions").get(0);
        assertThat(call.idempotencyKey()).as("I2").isEqualTo("checkout:chk-1");
        assertThat(call.stripeVersion()).as("the pinned API version is sent").isEqualTo("2026-10-28.endive");
        Map<String, Object> params = call.params();
        assertThat(params).containsEntry("mode", "subscription").containsEntry("success_url", ORIGIN + "/billing/return").containsEntry("cancel_url", ORIGIN + "/billing/cancelled")
                .containsEntry("client_reference_id", "chk-1").containsEntry("allow_promotion_codes", false).containsEntry("expires_at", expires.getEpochSecond());
        assertThat(params).as("PO U5: no stored customer").doesNotContainKey("customer");
        assertThat(params.get("allowed_payment_method_types")).as("PO D2").isEqualTo(List.of("card"));
        assertThat(params.get("automatic_tax")).as("PO Q4").isEqualTo(Map.of("enabled", false));
        assertThat(params.get("line_items")).isEqualTo(List.of(Map.of("price", "price_synthetic_m4", "quantity", 1L)));
        assertThat(params.get("metadata")).isEqualTo(Map.of(PaymentMetadata.CHECKOUT_ID, "chk-1", PaymentMetadata.BUSINESS_ID, "biz-1"));
        assertThat(params.get("subscription_data")).as("PO F2: never the checkout id on the subscription")
                .isEqualTo(Map.of("metadata", Map.of(PaymentMetadata.BUSINESS_ID, "biz-1")));
    }

    @Test
    void aSessionIsRefusedWithoutUsableUrlsAPriceOrAnAllowedLifetimeOrWithAStoredCustomer() {
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.createdSession("cs_x")));
        Instant fine = Instant.now().plus(2, ChronoUnit.HOURS);
        List<StripeSettings> bad = List.of(new StripeSettings(null, null, null), new StripeSettings(ORIGIN + "/r", ORIGIN + "/c", null),
                new StripeSettings("https://evil.example/r", ORIGIN + "/c", ORIGIN), new StripeSettings("http://app.example.test/r", "http://app.example.test/c", "http://app.example.test"),
                new StripeSettings("https://user@app.example.test/r", ORIGIN + "/c", ORIGIN), new StripeSettings("/relative", ORIGIN + "/c", ORIGIN));
        for (StripeSettings s : bad) {
            assertThatThrownBy(() -> client(s).createSession(request(fine), "k")).as(s.problem()).isInstanceOf(ProviderRejectedException.class);
        }
        assertThatThrownBy(() -> client.createSession(new ProviderSessionRequest("chk-1", "biz-1", new Money(2500, Currency.USD), fine, PriceInterval.MONTH, null, null,
                Map.of(PaymentMetadata.CHECKOUT_ID, "chk-1", PaymentMetadata.BUSINESS_ID, "biz-1")), "k")).isInstanceOf(ProviderRejectedException.class);
        assertThatThrownBy(() -> client.createSession(new ProviderSessionRequest("chk-1", "biz-1", new Money(2500, Currency.USD), fine, PriceInterval.MONTH, "price_x", "cus_x",
                Map.of(PaymentMetadata.CHECKOUT_ID, "chk-1", PaymentMetadata.BUSINESS_ID, "biz-1")), "k")).as("PO U5").isInstanceOf(ProviderRejectedException.class);
        assertThatThrownBy(() -> client.createSession(request(Instant.now().plus(20, ChronoUnit.MINUTES)), "k")).as("under 30 minutes").isInstanceOf(ProviderRejectedException.class);
        assertThatThrownBy(() -> client.createSession(request(Instant.now().plus(25, ChronoUnit.HOURS)), "k")).as("over 24 hours").isInstanceOf(ProviderRejectedException.class);
        assertThat(stub.calls("POST", "/v1/checkout/sessions")).as("nothing reached Stripe").isEmpty();
        assertThat(new StripeSettings("http://localhost:5173/r?s={CHECKOUT_SESSION_ID}", "http://127.0.0.1:5173/c", "http://localhost:5173, http://127.0.0.1:5173").problem()).isNull();
    }

    @Test
    void providerErrorsAreClassifiedWithoutEchoingTheirMessages() {
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.timeout());
        assertThatThrownBy(() -> client.createSession(request(Instant.now().plus(2, ChronoUnit.HOURS)), "k")).isInstanceOf(ProviderTimeoutException.class);
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.error(500, "api_error", "server_error"));
        assertThatThrownBy(() -> client.createSession(request(Instant.now().plus(2, ChronoUnit.HOURS)), "k")).isInstanceOf(ProviderTimeoutException.class);
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.error(429, "invalid_request_error", "rate_limit"));
        assertThatThrownBy(() -> client.createSession(request(Instant.now().plus(2, ChronoUnit.HOURS)), "k")).isInstanceOf(ProviderTimeoutException.class);
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.error(400, "invalid_request_error", "parameter_invalid_integer"));
        assertThatThrownBy(() -> client.createSession(request(Instant.now().plus(2, ChronoUnit.HOURS)), "k")).isInstanceOf(ProviderRejectedException.class)
                .hasMessageNotContaining("synthetic").hasMessageContaining("parameter_invalid_integer");
    }

    // ---- session expiry ----

    private void session(String ref, String status) {
        stub.on("GET", "/v1/checkout/sessions/" + ref, c -> StripeStub.ok(StripeFixtures.json(Map.of("id", ref, "object", "checkout.session", "status", status))));
    }

    @Test
    void expiryAsksStripeOnlyForAnOpenSessionAndReportsACompletedOne() {
        session("cs_open", "open");
        stub.on("POST", "/v1/checkout/sessions/cs_open/expire", c -> StripeStub.ok(StripeFixtures.json(Map.of("id", "cs_open", "object", "checkout.session", "status", "expired"))));
        assertThat(client.expireSession("cs_open", "checkout-expire:1")).isEqualTo(ExpireResult.EXPIRED);
        assertThat(stub.calls("POST", "/v1/checkout/sessions/cs_open/expire").get(0).idempotencyKey()).isEqualTo("checkout-expire:1");

        session("cs_done", "complete");
        assertThat(client.expireSession("cs_done", "k")).as("catalogue AC3").isEqualTo(ExpireResult.ALREADY_COMPLETE);
        assertThat(stub.calls("POST", "/v1/checkout/sessions/cs_done/expire")).isEmpty();

        // completed between the lookup and the expiry call: the provider's state decides
        List<String> statuses = new ArrayList<>(List.of("open", "complete"));
        stub.on("GET", "/v1/checkout/sessions/cs_race", c -> StripeStub.ok(StripeFixtures.json(Map.of("id", "cs_race", "object", "checkout.session", "status", statuses.remove(0)))));
        stub.on("POST", "/v1/checkout/sessions/cs_race/expire", c -> StripeStub.error(400, "invalid_request_error", "checkout_session_already_complete"));
        assertThat(client.expireSession("cs_race", "k")).isEqualTo(ExpireResult.ALREADY_COMPLETE);
    }

    // ---- signatures ----

    @Test
    void signaturesAreVerifiedWithTheCurrentOrPreviousSecretWithinTheTolerance() throws Exception {
        SignatureVerifier v = client.signatureVerifier();
        String payload = StripeFixtures.event("invoice.paid", Map.of("id", "in_synthetic_sig", "object", "invoice"));
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        assertThat(v.verify(headers("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, WEBHOOK)), body)).as("current").isTrue();
        assertThat(v.verify(headers("stripe-signature", Webhook.Signature.generateSignatureHeader(payload, PREVIOUS)), body)).as("previous, case-insensitive header").isTrue();
        String two = "t=" + now + ",v1=" + sig(payload, "whsec_" + "other", now) + ",v1=" + sig(payload, WEBHOOK, now);
        assertThat(v.verify(headers("Stripe-Signature", two), body)).as("any v1 entry").isTrue();
        assertThat(v.verify(headers("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, "whsec_" + "wrongSecret000000000001")), body)).as("wrong secret").isFalse();
        assertThat(v.verify(headers("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, WEBHOOK)), (payload + " ").getBytes(StandardCharsets.UTF_8))).as("tampered").isFalse();
        assertThat(v.verify(headers("Stripe-Signature", "t=" + (now - 301) + ",v1=" + sig(payload, WEBHOOK, now - 301)), body)).as("outside 300 s").isFalse();
        assertThat(v.verify(headers("Stripe-Signature", "t=" + (now - 290) + ",v1=" + sig(payload, WEBHOOK, now - 290)), body)).as("inside 300 s").isTrue();
        assertThat(v.verify(headers("Other", "x"), body)).as("missing header").isFalse();
        assertThat(new StripeProviderClient(credentials(null), new StripeSettings(null, null, null), stub, Clock.systemUTC()).signatureVerifier()
                .verify(headers("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, PREVIOUS)), body)).as("no rotation: previous refused").isFalse();
    }

    private static String sig(String payload, String secret, long t) throws Exception {
        String header = Webhook.Signature.generateSignatureHeader(payload, secret, t);
        return header.substring(header.indexOf("v1=") + 3);
    }

    @Test
    void bothInitialEventsNameTheFirstInvoiceAndNothingElseNamesAPayment() {
        SignatureVerifier v = client.signatureVerifier();
        byte[] completed = StripeFixtures.event("checkout.session.completed", Map.of("id", "cs_1", "object", "checkout.session", "invoice", "in_first")).getBytes(StandardCharsets.UTF_8);
        byte[] paid = StripeFixtures.event("invoice.paid", Map.of("id", "in_first", "object", "invoice")).getBytes(StandardCharsets.UTF_8);
        byte[] noInvoice = StripeFixtures.event("checkout.session.completed", Map.of("id", "cs_1", "object", "checkout.session")).getBytes(StandardCharsets.UTF_8);
        byte[] other = StripeFixtures.event("customer.subscription.deleted", Map.of("id", "sub_1", "object", "subscription")).getBytes(StandardCharsets.UTF_8);
        assertThat(v.paymentReference(completed)).contains("in_first");
        assertThat(v.paymentReference(paid)).contains("in_first");
        assertThat(v.paymentReference(noInvoice)).isEmpty();
        assertThat(v.paymentReference(other)).isEmpty();
        InboundEvent e = v.identify(paid);
        assertThat(e.eventId()).startsWith("evt_");
        assertThat(e.type()).isEqualTo("invoice.paid");
        assertThatThrownBy(() -> v.identify("{\"id\":\"x\",\"type\":\"t\"}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- verification: qualifying evidence (U1) and correlation (D6) ----

    @Test
    void aQualifyingFirstInvoiceIsVerifiedWithItsCheckoutAndTheLinePeriod() {
        Paid p = paid();
        VerifiedPayment v = verify(p);
        assertThat(v.providerPaymentRef()).as("U1: the first invoice is the identity").isEqualTo(p.invoiceRef);
        assertThat(v.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(v.amount()).isEqualTo(new Money(2500, Currency.USD));
        assertThat(v.paidAt()).isEqualTo(Instant.ofEpochSecond(p.paidAt));
        assertThat(v.metadata()).containsEntry(PaymentMetadata.CHECKOUT_ID, "chk-1").containsEntry(PaymentMetadata.BUSINESS_ID, "biz-1")
                .containsEntry(PaymentMetadata.PROVIDER_SESSION_REF, p.sessionRef);
        ProviderBilling b = v.billing();
        assertThat(b.qualifying()).isTrue();
        assertThat(b).isEqualTo(new ProviderBilling(p.invoiceRef, p.subscriptionRef, p.customerRef, "price_synthetic_m4", PriceInterval.MONTH,
                Instant.ofEpochSecond(p.periodStart), Instant.ofEpochSecond(p.periodEnd), p.paymentIntentRef, null));
        assertThat(stub.calls("GET", "/v1/checkout/sessions").get(0).params()).containsEntry("subscription", p.subscriptionRef);
    }

    private void assertNotQualifying(String label, Consumer<Paid> change) {
        stub.reset();
        Paid p = paid();
        change.accept(p);
        VerifiedPayment v = verify(p);
        assertThat(v.billing()).as(label).isNotNull();
        assertThat(v.billing().qualifying()).as(label).isFalse();
        assertThat(v.billing().evidenceProblem()).as(label).isNotBlank();
    }

    @Test
    void anythingButExactlyOneQualifyingCardPaymentForTheWholeAmountIsNotEvidence() {
        assertNotQualifying("customer credit", p -> { p.invoice.put("starting_balance", -500); p.invoice.put("amount_paid", 2500); });
        assertNotQualifying("paid off Stripe", p -> p.invoice.put("amount_paid_off_stripe", 2500));
        assertNotQualifying("overpaid", p -> p.invoice.put("amount_overpaid", 100));
        assertNotQualifying("credit note", p -> p.invoice.put("pre_payment_credit_notes_amount", 100));
        assertNotQualifying("total differs", p -> p.invoice.put("total", 3000));
        assertNotQualifying("tax", p -> p.invoice.put("total_taxes", List.of(Map.of("amount", 100))));
        assertNotQualifying("discount", p -> p.invoice.put("total_discount_amounts", List.of(Map.of("amount", 100, "discount", "di_1"))));
        assertNotQualifying("two paid records", p -> p.paymentRecords().add(new LinkedHashMap<>(p.paymentRecords().get(0))));
        assertNotQualifying("an open record too", p -> { Map<String, Object> open = new LinkedHashMap<>(p.paymentRecords().get(0)); open.put("status", "open"); p.paymentRecords().add(open); });
        assertNotQualifying("no paid record", p -> p.paymentRecords().clear());
        assertNotQualifying("not a PaymentIntent", p -> p.paymentRecords().get(0).put("payment", Map.of("type", "out_of_band_payment")));
        assertNotQualifying("record amount", p -> p.paymentRecords().get(0).put("amount_paid", 2000));
        assertNotQualifying("intent not succeeded", p -> p.paymentIntent.put("status", "processing"));
        assertNotQualifying("intent received less", p -> p.paymentIntent.put("amount_received", 2000));
        assertNotQualifying("not a card", p -> p.charge().put("payment_method_details", Map.of("type", "us_bank_account")));
        assertNotQualifying("not captured", p -> p.charge().put("captured", false));
        assertNotQualifying("refunded", p -> p.charge().put("refunded", true));
        assertNotQualifying("two lines", p -> ((List<Map<String, Object>>) ((Map<String, Object>) p.invoice.get("lines")).get("data")).add(new LinkedHashMap<>(p.line())));
        assertNotQualifying("quantity 2", p -> p.line().put("quantity", 2));
        assertNotQualifying("period differs from the subscription", p -> p.item().put("current_period_end", p.periodEnd + 3600));
        assertNotQualifying("interval_count 2", p -> p.recurring().put("interval_count", 2));
        assertNotQualifying("weekly", p -> p.recurring().put("interval", "week"));
        assertNotQualifying("subscription not active", p -> p.subscription.put("status", "incomplete"));
        assertNotQualifying("other price on the subscription", p -> ((Map<String, Object>) p.item().get("price")).put("id", "price_other"));
    }

    @Test
    void anUnpaidOrIncompleteInvoiceOrSessionIsNeverEvidence() {
        Paid p = paid();
        p.invoice.put("status", "open");
        assertThat(verify(p).status()).isEqualTo(ProviderPaymentStatus.PENDING);
        p.invoice.put("status", "void");
        assertThat(verify(p).status()).isEqualTo(ProviderPaymentStatus.FAILED);
        p.invoice.put("status", "paid");
        p.session.put("payment_status", "unpaid");
        assertThat(verify(p).status()).as("U1: an unpaid session grants nothing").isEqualTo(ProviderPaymentStatus.PENDING);
        p.session.put("payment_status", "paid");
        ((Map<String, Object>) p.invoice.get("status_transitions")).put("paid_at", null);
        assertThat(verify(p).status()).isEqualTo(ProviderPaymentStatus.PENDING);
    }

    @Test
    void onlyAnInitialInvoiceOfOneCompleteAgafariCheckoutCarriesAgafariMetadata() {
        Paid p = paid();
        p.invoice.put("billing_reason", "subscription_cycle");
        assertThat(verify(p).metadata()).as("D6: a renewal is not initial").isEmpty();
        assertThat(verify(p).billing()).isNull();
        p.invoice.put("billing_reason", "subscription_update");
        assertThat(verify(p).metadata()).isEmpty();
        p.invoice.put("billing_reason", "subscription_create");
        p.extraSessions.add(new LinkedHashMap<>(p.session));
        assertThat(verify(p).metadata()).as("two sessions: ambiguous").isEmpty();
        p.extraSessions.clear();
        p.session.put("invoice", "in_other");
        assertThat(verify(p).metadata()).as("the session's invoice isn't this one").isEmpty();
        p.session.put("invoice", p.invoiceRef);
        ((Map<String, Object>) p.session.get("metadata")).remove("agafari_checkout_id");
        assertThat(verify(p).metadata()).isEmpty();
    }

    /** D6/F2: a historical checkout id on the subscription is never used for identity. */
    @Test
    void subscriptionMetadataNeverIdentifiesTheCheckout() {
        Paid p = paid();
        ((Map<String, Object>) p.subscription.get("metadata")).put("agafari_checkout_id", "chk-HISTORICAL");
        ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) p.invoice.get("parent")).get("subscription_details")).get("metadata")).put("agafari_checkout_id", "chk-HISTORICAL");
        assertThat(verify(p).metadata()).containsEntry(PaymentMetadata.CHECKOUT_ID, "chk-1");
        p.invoice.put("billing_reason", "subscription_cycle");
        assertThat(verify(p).metadata()).as("a renewal with a historical id is still unattributed").isEmpty();
    }

    @Test
    void aLiveModeObjectOrANonInvoiceReferenceIsRefused() {
        Paid p = paid();
        p.invoice.put("livemode", true);
        assertThatThrownBy(() -> verify(p)).isInstanceOf(ProviderRejectedException.class);
        assertThatThrownBy(() -> client.verifyPayment("cs_not_an_invoice")).isInstanceOf(ProviderRejectedException.class);
        stub.on("GET", "/v1/invoices/in_slow", c -> StripeStub.timeout());
        assertThatThrownBy(() -> client.verifyPayment("in_slow")).isInstanceOf(ProviderTimeoutException.class);
    }

    // ---- D4: re-fetch and cancellation ----

    @Test
    void theSubscriptionOfAPaymentIsReFetchedWithItsCheckoutOwnershipAndTheCustomersOtherOpenInvoices() {
        Paid p = paid();
        ProviderSubscriptionState s = client.subscriptionOfPayment(p.invoiceRef);
        assertThat(s).isEqualTo(new ProviderSubscriptionState(p.subscriptionRef, p.customerRef, ProviderSubscriptionState.Status.ACTIVE, "chk-1", "biz-1", false, false));
        p.openInvoices.add(Map.of("id", "in_own_open", "object", "invoice", "status", "open", "parent",
                Map.of("type", "subscription_details", "subscription_details", Map.of("subscription", p.subscriptionRef))));
        assertThat(client.subscriptionOfPayment(p.invoiceRef).otherOpenInvoices()).as("its own open invoice doesn't count").isFalse();
        p.openInvoices.add(Map.of("id", "in_other_open", "object", "invoice", "status", "open", "parent",
                Map.of("type", "subscription_details", "subscription_details", Map.of("subscription", "sub_other"))));
        assertThat(client.subscriptionOfPayment(p.invoiceRef).otherOpenInvoices()).isTrue();
        assertThat(client.subscriptionOfPayment("in_missing").status()).isEqualTo(ProviderSubscriptionState.Status.NOT_FOUND);
        p.subscription.put("status", "canceled");
        assertThat(client.subscriptionOfPayment(p.invoiceRef).ended()).isTrue();
    }

    @Test
    void cancellationIsImmediateWithoutInvoiceOrProrationAndAnAlreadyCancelledOneIsRecognised() {
        Paid p = paid();
        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> {
            p.subscription.put("status", "canceled");
            return StripeStub.ok(StripeFixtures.json(p.subscription));
        });
        assertThat(client.cancelSubscription(p.subscriptionRef)).isEqualTo(SubscriptionCancelResult.CANCELLED);
        StripeStub.Call call = stub.calls("DELETE", "/v1/subscriptions/" + p.subscriptionRef).get(0);
        assertThat(call.params()).containsEntry("invoice_now", false).containsEntry("prorate", false);
        assertThat(call.idempotencyKey()).as("Stripe: no idempotency key on DELETE").isNull();

        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> StripeStub.error(400, "invalid_request_error", "resource_already_canceled"));
        assertThat(client.cancelSubscription(p.subscriptionRef)).isEqualTo(SubscriptionCancelResult.ALREADY_CANCELLED);

        stub.on("DELETE", "/v1/subscriptions/" + p.subscriptionRef, c -> StripeStub.timeout());
        assertThatThrownBy(() -> client.cancelSubscription(p.subscriptionRef)).as("uncertain").isInstanceOf(ProviderTimeoutException.class);
    }

    /** T11: the synthetic fixtures are test-mode, labelled, and parse with the pinned SDK at its pinned API version. */
    @Test
    void syntheticFixturesAreTestModeLabelledAndParseWithThePinnedSdk() {
        Paid p = paid();
        for (Map<String, Object> o : List.of(p.invoice, p.paymentIntent, p.charge(), p.subscription, p.session)) assertThat(o).containsEntry("livemode", false);
        for (String json : List.of(p.checkoutCompletedEvent(), p.invoicePaidEvent())) {
            com.stripe.model.Event event = com.stripe.net.ApiResource.GSON.fromJson(json, com.stripe.model.Event.class);
            assertThat(event.getApiVersion()).isEqualTo(com.stripe.Stripe.API_VERSION).isEqualTo("2026-10-28.endive");
            assertThat(event.getLivemode()).isFalse();
            assertThat(event.getId()).startsWith("evt_synthetic");
        }
        assertThat(StripeFixtures.SYNTHETIC).startsWith("SYNTHETIC");
    }

    @Test
    void refundsAndListingStillRefuse() {
        assertThatThrownBy(() -> client.submitRefund("in_x", new Money(1, Currency.USD), "k", Map.of())).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("SUB-R5-05");
        assertThatThrownBy(() -> client.findRefund("in_x", "r")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("SUB-R5-05");
        assertThatThrownBy(() -> client.listSince(Instant.now(), com.agafari.com.billing.port.RecordType.PAYMENT)).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SUB-R5-06");
    }
}
