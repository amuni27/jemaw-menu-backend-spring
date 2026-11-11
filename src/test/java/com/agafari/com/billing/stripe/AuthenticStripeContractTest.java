package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Currency;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.PaymentMetadata;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.billing.port.ExpireResult;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderSession;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.SubscriptionCancelResult;
import com.agafari.com.billing.port.VerifiedPayment;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stripe.Stripe;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceItem;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.ApiResource;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeResponse;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.fixtureId;
import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.ids;
import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.num;
import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.raw;
import static com.agafari.com.billing.stripe.AuthenticStripeFixtures.str;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF R5-02 Q6 guide §15 (A1–A11): the real Stripe adapter and the real SDK (34.1.0, API {@code 2026-10-28.endive})
 * against {@link AuthenticStripeFixtures AUTHENTIC} sandbox responses, served byte for byte through {@link StripeStub}.
 * No network. Where a test composes authentic objects into a state Stripe was not asked for (A2), it says so.
 */
class AuthenticStripeContractTest {

    private static final String KEY = "sk_" + "test_" + "SyntheticQ6Contract0000000001";
    private static final String WEBHOOK = "whsec_" + "SyntheticQ6Contract0000000002";
    private static final String PREVIOUS = "whsec_" + "SyntheticQ6Contract0000000003";
    private static final String ORIGIN = "https://app.example.test";

    private final StripeStub stub = new StripeStub();
    private StripeProviderClient client;

    @BeforeEach
    void setUp() {
        stub.reset();
        StripeCredentials credentials = StripeCredentials.fromEnvironment(Map.of(StripeCredentials.ENV_SECRET_KEY, KEY,
                StripeCredentials.ENV_WEBHOOK_SECRET, WEBHOOK, StripeCredentials.ENV_WEBHOOK_SECRET_PREVIOUS, PREVIOUS)::get);
        // the adapter's clock at the authentic session-creation time, so the 30 min – 24 h expiry window is checked as it was
        Clock atCapture = Clock.fixed(Instant.ofEpochSecond(num("a_session_create", "created")), ZoneOffset.UTC);
        client = new StripeProviderClient(credentials, new StripeSettings(ORIGIN + "/billing/return", ORIGIN + "/billing/cancelled", ORIGIN), stub, atCapture);
    }

    private static StripeResponse ok(String fixture) {
        return StripeStub.ok(raw(fixture));
    }

    private static StripeResponse authenticError(int status, String fixture) {
        return new StripeResponse(status, HttpHeaders.of(Map.of("request-id", List.of("req_q6"))), raw(fixture));
    }

    /** The re-fetches of an initial payment (scenario A or B), exactly as captured. */
    private void serveInitial(String p) {
        String in = str(p + "_session_after", "invoice"), sub = str(p + "_session_after", "subscription");
        String pi = str(p + "_payment_intent", "id"), cs = str(p + "_session_after", "id");
        stub.on("GET", "/v1/invoices/" + in, c -> ok(c.params().containsKey("expand") ? p + "_invoice_verify" : p + "_invoice_plain"));
        stub.on("GET", "/v1/payment_intents/" + pi, c -> ok(p + "_payment_intent"));
        stub.on("GET", "/v1/subscriptions/" + sub, c -> ok(p + "_subscription"));
        stub.on("GET", "/v1/checkout/sessions/" + cs, c -> ok(p + "_session_after"));
        stub.on("GET", "/v1/checkout/sessions", c -> sub.equals(c.params().get("subscription")) ? ok(p + "_sessions_by_sub") : ok("r_sessions_by_sub"));
        stub.on("GET", "/v1/invoices", c -> ok(p + "_open_invoices_none"));
    }

    // ---- A1: a qualifying first invoice (U1, Q2, D6) ----

    @ParameterizedTest(name = "scenario {0}")
    @ValueSource(strings = {"a", "b"})
    void a1_anAuthenticFirstInvoiceIsVerifiedWithItsCheckoutLinePeriodAndMoneyMovement(String p) {
        serveInitial(p);
        String in = str(p + "_session_after", "invoice");
        String chk = fixtureId(p.equals("a") ? "CHK_A" : "CHK_B"), biz = fixtureId(p.equals("a") ? "BIZ_A" : "BIZ_B");

        VerifiedPayment v = client.verifyPayment(in);

        assertThat(v.providerPaymentRef()).as("U1: the first invoice is the identity").isEqualTo(in);
        assertThat(v.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(v.amount()).isEqualTo(new Money(2500, Currency.USD));
        assertThat(v.paidAt()).isEqualTo(Instant.ofEpochSecond(num(p + "_invoice_verify", "status_transitions.paid_at")));
        assertThat(v.metadata()).containsEntry(PaymentMetadata.CHECKOUT_ID, chk).containsEntry(PaymentMetadata.BUSINESS_ID, biz)
                .containsEntry(PaymentMetadata.PROVIDER_SESSION_REF, str(p + "_session_after", "id"));
        ProviderBilling b = v.billing();
        assertThat(b.evidenceProblem()).as("U1 evidence on authentic objects").isNull();
        assertThat(b).isEqualTo(new ProviderBilling(in, str(p + "_subscription", "id"), str(p + "_subscription", "customer"), str("price_m4_month", "id"),
                PriceInterval.MONTH, Instant.ofEpochSecond(num(p + "_invoice_verify", "lines.data[0].period.start")),
                Instant.ofEpochSecond(num(p + "_invoice_verify", "lines.data[0].period.end")), str(p + "_payment_intent", "id"), null));
        StripeStub.Call invoiceCall = stub.calls("GET", "/v1/invoices/" + in).get(0);
        assertThat(invoiceCall.params().get("expand")).as("the expansions the capture proved").isEqualTo(List.of("payments", "amount_paid_off_stripe"));
        assertThat(invoiceCall.stripeVersion()).isEqualTo("2026-10-28.endive");
    }

    // ---- A2: an incomplete (mid-3DS) session is never evidence ----

    @Test
    void a2_theAuthenticMidChallengeSessionIsNeverEvidence() {
        // authentic object: the session while the 3DS challenge was open
        assertThat(str("b_session_during_3ds", "status")).isEqualTo("open");
        assertThat(str("b_session_during_3ds", "payment_status")).isEqualTo("unpaid");
        assertThat(str("b_session_during_3ds", "invoice")).as("no invoice, so no ledger identity exists yet").isNull();
        assertThat(str("b_session_during_3ds", "subscription")).isNull();
        // composed (labelled): the paid invoice re-fetched while Stripe still reports that session for the subscription
        serveInitial("b");
        stub.on("GET", "/v1/checkout/sessions", c -> StripeStub.ok("{\"object\":\"list\",\"has_more\":false,\"data\":[" + raw("b_session_during_3ds") + "]}"));
        VerifiedPayment v = client.verifyPayment(str("b_session_after", "invoice"));
        assertThat(v.metadata()).as("an open, unpaid session never correlates (no entitlement)").isEmpty();
        assertThat(v.billing()).isNull();
    }

    // ---- A3, A4: renewals and uncorrelated initial invoices carry no Agafari metadata (D6, F2) ----

    private void serveRenewal() {
        String sub = str("r_subscription_create", "id");
        stub.on("GET", "/v1/invoices/" + str("r_invoice_cycle_verify", "id"), c -> ok("r_invoice_cycle_verify"));
        stub.on("GET", "/v1/invoices/" + str("r_invoice_create_verify", "id"), c -> ok("r_invoice_create_verify"));
        stub.on("GET", "/v1/checkout/sessions", c -> sub.equals(c.params().get("subscription")) ? ok("r_sessions_by_sub") : StripeStub.error(400, "invalid_request_error", "unexpected"));
    }

    @Test
    void a3_anAuthenticRenewalInvoiceIsUnattributedAndNeverLooksForACheckout() {
        serveRenewal();
        String cycle = str("r_invoice_cycle_verify", "id");
        assertThat(str("r_invoice_cycle_verify", "billing_reason")).isEqualTo("subscription_cycle");
        VerifiedPayment v = client.verifyPayment(cycle);
        assertThat(v.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(v.metadata()).as("D6: a renewal → quarantine").isEmpty();
        assertThat(v.billing()).isNull();
        assertThat(stub.calls("GET", "/v1/checkout/sessions")).as("not even correlated").isEmpty();
    }

    @Test
    void a3_anAuthenticSubscriptionCreateInvoiceWithoutACheckoutIsUnattributed() {
        serveRenewal();
        VerifiedPayment v = client.verifyPayment(str("r_invoice_create_verify", "id"));
        assertThat(ids("r_sessions_by_sub")).as("authentic: Stripe has no Checkout Session for it").isEmpty();
        assertThat(v.metadata()).isEmpty();
        assertThat(v.billing()).isNull();
    }

    @Test
    void a4_theHistoricalCheckoutIdOnAuthenticSubscriptionMetadataIsIgnored() {
        String historical = fixtureId("CHK_HIST");
        assertThat(str("r_subscription_create", "metadata.agafari_checkout_id")).isEqualTo(historical);
        assertThat(str("r_invoice_cycle_verify", "parent.subscription_details.metadata.agafari_checkout_id"))
                .as("authentic: Stripe copies subscription metadata onto every invoice").isEqualTo(historical);
        serveRenewal();
        assertThat(client.verifyPayment(str("r_invoice_cycle_verify", "id")).metadata()).doesNotContainValue(historical).isEmpty();
        assertThat(client.verifyPayment(str("r_invoice_create_verify", "id")).metadata()).doesNotContainValue(historical).isEmpty();
    }

    // ---- A5, A6: event identity and signatures over authentic bytes ----

    @Test
    void a5_authenticEventsNameTheirInvoiceAndAreIdentified() {
        SignatureVerifier v = client.signatureVerifier();
        for (String p : List.of("a", "b")) {
            String in = str(p + "_session_after", "invoice");
            byte[] completed = raw(p + "_event_session_completed").getBytes(StandardCharsets.UTF_8);
            byte[] paid = raw(p + "_event_invoice_paid").getBytes(StandardCharsets.UTF_8);
            assertThat(v.paymentReference(completed)).as(p + ": D-14 session event → first invoice").contains(in);
            assertThat(v.paymentReference(paid)).as(p + ": invoice.paid → the same invoice").contains(in);
            InboundEvent e = v.identify(completed);
            assertThat(e.eventId()).isEqualTo(str(p + "_event_session_completed", "id"));
            assertThat(e.type()).isEqualTo("checkout.session.completed");
        }
        byte[] renewal = raw("r_event_invoice_paid_cycle").getBytes(StandardCharsets.UTF_8);
        assertThat(v.paymentReference(renewal)).as("identity only; verification quarantines it").contains(str("r_invoice_cycle_verify", "id"));
    }

    @Test
    void a6_signaturesOverAuthenticEventBytes() {
        SignatureVerifier v = client.signatureVerifier();
        for (String name : List.of("a_event_session_completed", "a_event_invoice_paid", "b_event_session_completed", "b_event_invoice_paid", "r_event_invoice_paid_cycle")) {
            String payload = raw(name);
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            assertThat(v.verify(headers(sign(payload, WEBHOOK)), body)).as(name + " current").isTrue();
            assertThat(v.verify(headers(sign(payload, PREVIOUS)), body)).as(name + " previous").isTrue();
            assertThat(v.verify(headers(sign(payload, WEBHOOK)), (payload + " ").getBytes(StandardCharsets.UTF_8))).as(name + " tampered").isFalse();
            assertThat(v.verify(headers(sign(payload, "whsec_" + "otherQ6")), body)).as(name + " wrong secret").isFalse();
        }
    }

    private static String sign(String payload, String secret) {
        try {
            return Webhook.Signature.generateSignatureHeader(payload, secret);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> headers(String signature) {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.put("Stripe-Signature", signature);
        return h;
    }

    // ---- A7: the session request is the one the real API accepted ----

    @Test
    void a7_theAdaptersSessionRequestIsTheOneStripeAcceptedAndItsResponseMaps() {
        stub.on("POST", "/v1/checkout/sessions", c -> ok("a_session_create"));
        String chk = fixtureId("CHK_A"), biz = fixtureId("BIZ_A");
        Instant expires = Instant.ofEpochSecond(num("a_session_create", "expires_at"));
        ProviderSession s = client.createSession(new ProviderSessionRequest(chk, biz, new Money(2500, Currency.USD), expires, PriceInterval.MONTH,
                str("price_m4_month", "id"), null, Map.of(PaymentMetadata.CHECKOUT_ID, chk, PaymentMetadata.BUSINESS_ID, biz)), "checkout:" + chk);

        assertThat(s.providerSessionRef()).isEqualTo(str("a_session_create", "id"));
        assertThat(s.url()).as("present (its value is redacted in the fixture)").isNotNull();
        StripeStub.Call call = stub.calls("POST", "/v1/checkout/sessions").get(0);
        Map<String, String> sent = new TreeMap<>();
        flatten("", call.params(), sent);
        Map<String, String> captured = capturedForm("a_session_create");
        assertThat(sent.keySet()).as("exactly the parameters the real API accepted in the capture").isEqualTo(captured.keySet());
        captured.forEach((k, value) -> {
            if (!List.of("success_url", "cancel_url", "expires_at").contains(k)) assertThat(sent.get(k)).as(k).isEqualTo(value);
        });
        assertThat(call.idempotencyKey()).isEqualTo(capturedIdempotencyKey("a_session_create")).isEqualTo("checkout:" + chk);
        assertThat(call.stripeVersion()).isEqualTo("2026-10-28.endive");
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object v, Map<String, String> out) {
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "[" + k + "]", x, out));
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) flatten(prefix + "[" + i + "]", l.get(i), out);
        } else {
            out.put(prefix, String.valueOf(v));
        }
    }

    private static List<String> argv(String fixture) {
        List<String> a = new ArrayList<>();
        AuthenticStripeFixtures.manifest().getAsJsonObject("files").getAsJsonObject(fixture + ".json").getAsJsonArray("argv").forEach(x -> a.add(x.getAsString()));
        return a;
    }

    private static Map<String, String> capturedForm(String fixture) {
        List<String> a = argv(fixture);
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i < a.size() - 1; i++) {
            if (a.get(i).equals("-d")) {
                String[] kv = a.get(i + 1).split("=", 2);
                form.put(kv[0], kv[1]);
            }
        }
        return form;
    }

    private static String capturedIdempotencyKey(String fixture) {
        List<String> a = argv(fixture);
        return a.get(a.indexOf("-i") + 1);
    }

    // ---- A8: expiry ----

    @Test
    void a8_expiryWithAuthenticSessionsAndTheAuthenticAlreadyCompleteError() {
        String open = str("x_session_create", "id");
        stub.on("GET", "/v1/checkout/sessions/" + open, c -> ok("x_session_create"));
        stub.on("POST", "/v1/checkout/sessions/" + open + "/expire", c -> ok("x_session_expire"));
        assertThat(client.expireSession(open, "expire:" + fixtureId("CHK_X"))).isEqualTo(ExpireResult.EXPIRED);

        String completed = str("a_session_after", "id");
        stub.on("GET", "/v1/checkout/sessions/" + completed, c -> ok("a_session_after"));
        assertThat(client.expireSession(completed, "k")).as("AC3: no expire call for a completed session").isEqualTo(ExpireResult.ALREADY_COMPLETE);
        assertThat(stub.calls("POST", "/v1/checkout/sessions/" + completed + "/expire")).isEmpty();

        // race: open at lookup, completed at the expire call; Stripe's authentic error, then the re-fetch decides
        List<String> states = new ArrayList<>(List.of("a_session_create", "a_session_after"));
        stub.on("GET", "/v1/checkout/sessions/" + completed, c -> ok(states.remove(0)));
        stub.on("POST", "/v1/checkout/sessions/" + completed + "/expire", c -> authenticError(400, "x_expire_completed"));
        assertThat(client.expireSession(completed, "k2")).isEqualTo(ExpireResult.ALREADY_COMPLETE);
    }

    // ---- A9: the D4 re-fetch (C2, C5) ----

    @Test
    void a9_theSubscriptionOfAnAuthenticPaymentWithOwnershipAndTheCustomersOtherOpenInvoices() {
        serveInitial("a");
        String in = str("a_session_after", "invoice");
        ProviderSubscriptionState s = client.subscriptionOfPayment(in);
        assertThat(s).isEqualTo(new ProviderSubscriptionState(str("a_subscription", "id"), str("a_subscription", "customer"), ProviderSubscriptionState.Status.ACTIVE,
                fixtureId("CHK_A"), fixtureId("BIZ_A"), false, false));
        assertThat(stub.calls("GET", "/v1/invoices").get(0).params()).containsEntry("customer", str("a_subscription", "customer")).containsEntry("status", "open");

        stub.on("GET", "/v1/invoices", c -> ok("o_open_invoices_other"));
        assertThat(client.subscriptionOfPayment(in).otherOpenInvoices()).as("C5: the authentic one-off open invoice").isTrue();
        stub.on("GET", "/v1/invoices", c -> ok("o_open_invoices_after_void"));
        assertThat(client.subscriptionOfPayment(in).otherOpenInvoices()).as("after void").isFalse();
    }

    // ---- A10: cancellation ----

    @ParameterizedTest(name = "second cancel answered with HTTP {0}")
    @ValueSource(ints = {400, 404})
    void a10_authenticCancellationAndTheAuthenticSecondCancelError(int status) {
        String sub = str("a_subscription", "id");
        stub.on("DELETE", "/v1/subscriptions/" + sub, c -> ok("c_cancel"));
        assertThat(client.cancelSubscription(sub)).isEqualTo(SubscriptionCancelResult.CANCELLED);
        assertThat(stub.calls("DELETE", "/v1/subscriptions/" + sub).get(0).params()).containsEntry("invoice_now", false).containsEntry("prorate", false);

        // the capture recorded the error body, not its HTTP status (D-17): both statuses Stripe uses for it are tried
        stub.on("DELETE", "/v1/subscriptions/" + sub, c -> authenticError(status, "c_cancel_again"));
        stub.on("GET", "/v1/subscriptions/" + sub, c -> ok("c_subscription_after"));
        assertThat(client.cancelSubscription(sub)).isEqualTo(SubscriptionCancelResult.ALREADY_CANCELLED);
        assertThat(ids("c_invoices_after")).as("authentic: no final invoice after invoice_now=false, prorate=false")
                .containsExactly(str("a_invoice_verify", "id"));
    }

    // ---- the flow tests' re-keying changes ids only ----

    @Test
    void rekeyingChangesOnlyStripeIdValuesNeverAKeyEnumOrOtherValue() {
        AuthenticStripeFixtures.Rekey rk = new AuthenticStripeFixtures.Rekey("Q6guard");
        int ids = 0;
        for (String name : AuthenticFixtureIntegrityTest.APPROVED) {
            ids += compare(name, "$", AuthenticStripeFixtures.json(name), com.google.gson.JsonParser.parseString(rk.fixture(name)));
        }
        assertThat(ids).as("ids renamed across the set").isGreaterThan(100);
    }

    private static int compare(String name, String path, JsonElement original, JsonElement rekeyed) {
        if (original.isJsonObject()) {
            assertThat(rekeyed.getAsJsonObject().keySet()).as(name + " " + path + ": keys unchanged").isEqualTo(original.getAsJsonObject().keySet());
            int n = 0;
            for (String k : original.getAsJsonObject().keySet()) n += compare(name, path + "." + k, original.getAsJsonObject().get(k), rekeyed.getAsJsonObject().get(k));
            return n;
        }
        if (original.isJsonArray()) {
            int n = 0;
            for (int i = 0; i < original.getAsJsonArray().size(); i++) n += compare(name, path + "[" + i + "]", original.getAsJsonArray().get(i), rekeyed.getAsJsonArray().get(i));
            return n;
        }
        if (original.isJsonPrimitive() && original.getAsJsonPrimitive().isString() && !original.equals(rekeyed)) {
            assertThat(rekeyed.getAsString()).as(name + " " + path).isEqualTo(original.getAsString() + "Q6guard");
            assertThat(original.getAsString()).as(name + " " + path + ": only a real id is renamed").matches("[a-z]+_(test_)?[A-Za-z0-9]*[A-Z0-9][A-Za-z0-9]*");
            return 1;
        }
        assertThat(rekeyed).as(name + " " + path).isEqualTo(original);
        return 0;
    }

    // ---- A11: every authentic object parses with the pinned SDK into what the adapter reads ----

    @Test
    void a11_everyAuthenticObjectParsesWithTheSdkAtTheAdaptersPaths() {
        assertThat(Stripe.API_VERSION).isEqualTo("2026-10-28.endive");
        for (String name : AuthenticFixtureIntegrityTest.APPROVED) {
            JsonObject o = AuthenticStripeFixtures.json(name);
            if (o.has("error") || "list".equals(o.get("object").getAsString())) continue;
            Class<?> type = switch (o.get("object").getAsString()) {
                case "checkout.session" -> Session.class;
                case "invoice" -> Invoice.class;
                case "payment_intent" -> PaymentIntent.class;
                case "subscription" -> Subscription.class;
                case "event" -> Event.class;
                case "price" -> Price.class;
                case "product" -> Product.class;
                case "customer" -> Customer.class;
                case "payment_method" -> PaymentMethod.class;
                case "invoiceitem" -> InvoiceItem.class;
                case "test_helpers.test_clock" -> TestClock.class;
                default -> throw new AssertionError(name + ": unexpected object type");
            };
            Object parsed = ApiResource.GSON.fromJson(raw(name), type);
            assertThat(parsed).as(name).isNotNull();
        }
        Invoice inv = ApiResource.GSON.fromJson(raw("b_invoice_verify"), Invoice.class);
        assertThat(inv.getParent().getSubscriptionDetails().getSubscription()).isEqualTo(str("b_subscription", "id"));
        assertThat(inv.getLines().getData().get(0).getPricing().getPriceDetails().getPrice()).isEqualTo(str("price_m4_month", "id"));
        assertThat(inv.getPayments().getData()).hasSize(1);
        assertThat(inv.getPayments().getData().get(0).getPayment().getPaymentIntent()).isEqualTo(str("b_payment_intent", "id"));
        assertThat(inv.getAmountPaidOffStripe()).isZero();
        assertThat(inv.getStatusTransitions().getPaidAt()).isEqualTo(num("b_invoice_verify", "status_transitions.paid_at"));
        PaymentIntent pi = ApiResource.GSON.fromJson(raw("b_payment_intent"), PaymentIntent.class);
        Charge ch = pi.getLatestChargeObject();
        assertThat(ch.getPaymentMethodDetails().getType()).isEqualTo("card");
        assertThat(ch.getPaymentMethodDetails().getCard().getThreeDSecure().getResult()).as("authentic 3DS result").isEqualTo("authenticated");
        Subscription sub = ApiResource.GSON.fromJson(raw("b_subscription"), Subscription.class);
        assertThat(sub.getItems().getData().get(0).getCurrentPeriodEnd()).isEqualTo(num("b_invoice_verify", "lines.data[0].period.end"));
        Session mid = ApiResource.GSON.fromJson(raw("b_session_during_3ds"), Session.class);
        assertThat(mid.getStatus()).isEqualTo("open");
        Event e = ApiResource.GSON.fromJson(raw("r_event_invoice_paid_cycle"), Event.class);
        assertThat(e.getApiVersion()).isEqualTo(Stripe.API_VERSION);
        JsonElement price = AuthenticStripeFixtures.at("price_m4_year", "recurring.interval");
        assertThat(price.getAsString()).isEqualTo("year");
    }
}
