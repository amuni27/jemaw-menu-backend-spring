package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Currency;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.PaymentMetadata;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF gate 8 (register §89; PO G8-A–C): the Stripe adapter talks only to the expected Stripe account.
 * <ul>
 *   <li>The account behind the key is checked (GET /v1/account) before the first Stripe operation, lazily, and remembered once
 *   it matches (G8-A).</li>
 *   <li>A mismatch refuses every operation, permanently, until a restart: fail-closed.</li>
 *   <li>A failed verification (bad key, no permission, network) refuses the operation and is checked again next time: never
 *   remembered as a pass.</li>
 *   <li>No expected account configured refuses everything.</li>
 *   <li>Before a checkout session, the configured Stripe Price must exist in that account and match the server's amount,
 *   currency and interval (G8-C).</li>
 *   <li>A webhook event that names another account is refused.</li>
 * </ul>
 * Stubbed HTTP, synthetic values only, no network.
 */
class StripeAccountIdentityTest {

    private static final String KEY = "sk_" + "test_" + "SyntheticGate8Unit00000000001";
    private static final String WEBHOOK = "whsec_" + "SyntheticGate8Unit00000000002";
    private static final String ORIGIN = "https://app.example.test";
    private static final String OTHER_ACCOUNT = "acct_" + "SyntheticSomeoneElse01";
    private static final String PRICE = "price_synthetic_gate8";

    private final StripeStub stub = new StripeStub();

    private static StripeCredentials credentials() {
        return StripeCredentials.fromEnvironment(Map.of(StripeCredentials.ENV_SECRET_KEY, KEY, StripeCredentials.ENV_WEBHOOK_SECRET, WEBHOOK)::get);
    }

    private StripeProviderClient client(String expectedAccount) {
        StripeSettings settings = new StripeSettings(ORIGIN + "/billing/return", ORIGIN + "/billing/cancelled", ORIGIN, ORIGIN + "/plans", "bpc_" + "SyntheticGate8");
        return new StripeProviderClient(credentials(), settings, expectedAccount, stub, Clock.systemUTC());
    }

    private static ProviderSessionRequest request() {
        return new ProviderSessionRequest("chk-1", "biz-1", new Money(2500, Currency.USD), Instant.now().plus(2, ChronoUnit.HOURS), PriceInterval.MONTH, PRICE, null,
                Map.of(PaymentMetadata.CHECKOUT_ID, "chk-1", PaymentMetadata.BUSINESS_ID, "biz-1"));
    }

    /** Every operation that reaches Stripe (refunds and listing still refuse as not authorized; capabilities and signatures are local). */
    private static Map<String, Consumer<PaymentProviderClient>> operations() {
        Map<String, Consumer<PaymentProviderClient>> ops = new LinkedHashMap<>();
        ops.put("create session", c -> c.createSession(request(), "checkout:chk-1"));
        ops.put("expire session", c -> c.expireSession("cs_synthetic", "expire:chk-1"));
        ops.put("verify payment", c -> c.verifyPayment("in_synthetic"));
        ops.put("subscription of payment", c -> c.subscriptionOfPayment("in_synthetic"));
        ops.put("invoice state", c -> c.invoiceState("in_synthetic"));
        ops.put("subscription state", c -> c.subscriptionState("sub_synthetic"));
        ops.put("portal session", c -> c.createPortalSession("cus_synthetic"));
        ops.put("cancel subscription", c -> c.cancelSubscription("sub_synthetic"));
        return ops;
    }

    private List<StripeStub.Call> nonAccountCalls() {
        return stub.calls().stream().filter(c -> !c.path().equals("/v1/account")).toList();
    }

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    // ---- account identity ----

    @Test
    void theMatchingAccountIsVerifiedOnceBeforeTheFirstCallAndThenRemembered() {
        StripeFixtures.registerPrice(stub, PRICE, 2500, "USD", "month");
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.createdSession("cs_synthetic_ok")));
        StripeProviderClient client = client(StripeStub.ACCOUNT);

        assertThat(stub.calls()).as("nothing at construction: verification is lazy (G8-A)").isEmpty();
        client.createSession(request(), "checkout:chk-1");
        client.createSession(request(), "checkout:chk-1");

        assertThat(stub.calls().get(0).path()).as("the account is checked before anything else").isEqualTo("/v1/account");
        assertThat(stub.calls("GET", "/v1/account")).as("remembered after it matched").hasSize(1);
        assertThat(stub.calls("POST", "/v1/checkout/sessions")).hasSize(2);
    }

    @Test
    void aKeyForAnotherAccountIsRefusedForEveryOperationAndStaysRefused() {
        stub.on("GET", "/v1/account", c -> StripeStub.account(OTHER_ACCOUNT)); // wrong-account credentials
        StripeProviderClient client = client(StripeStub.ACCOUNT);

        operations().forEach((name, op) -> assertThatThrownBy(() -> op.accept(client)).as(name)
                .isInstanceOf(ProviderRejectedException.class).hasMessageContaining("account"));
        assertThat(nonAccountCalls()).as("no operation reached Stripe").isEmpty();
        assertThat(stub.calls("GET", "/v1/account")).as("a mismatch is final until a restart: not asked again").hasSize(1);

        stub.on("GET", "/v1/account", c -> StripeStub.account(StripeStub.ACCOUNT)); // even if the answer changed
        assertThatThrownBy(() -> client.verifyPayment("in_synthetic")).isInstanceOf(ProviderRejectedException.class);
        assertThat(nonAccountCalls()).isEmpty();
    }

    @Test
    void aFailedVerificationRefusesTheCallAndIsCheckedAgainNextTime() {
        StripeProviderClient client = client(StripeStub.ACCOUNT);
        stub.on("GET", "/v1/account", c -> StripeStub.error(401, "invalid_request_error", "api_key_expired")); // a key Stripe rejects
        assertThatThrownBy(() -> client.invoiceState("in_synthetic")).isInstanceOf(ProviderRejectedException.class).hasMessageContaining("account");
        stub.on("GET", "/v1/account", c -> StripeStub.error(403, "invalid_request_error", "secret_key_required")); // a restricted key without permission
        assertThatThrownBy(() -> client.invoiceState("in_synthetic")).isInstanceOf(ProviderRejectedException.class);
        stub.on("GET", "/v1/account", c -> StripeStub.timeout());
        assertThatThrownBy(() -> client.invoiceState("in_synthetic")).as("a network failure is retryable").isInstanceOf(ProviderTimeoutException.class);
        stub.on("GET", "/v1/account", c -> StripeStub.error(500, "api_error", "server_error"));
        assertThatThrownBy(() -> client.invoiceState("in_synthetic")).isInstanceOf(ProviderTimeoutException.class);
        stub.on("GET", "/v1/account", c -> StripeStub.ok("{\"object\":\"account\"}"));
        assertThatThrownBy(() -> client.invoiceState("in_synthetic")).as("an answer without an id").isInstanceOf(ProviderRejectedException.class);
        assertThat(nonAccountCalls()).as("nothing else was called while unverified").isEmpty();
        assertThat(stub.calls("GET", "/v1/account")).as("never remembered as a pass").hasSize(5);
    }

    @Test
    void withoutAnExpectedAccountEveryOperationIsRefusedWithoutCallingStripe() {
        for (String missing : new String[] {null, "", "  "}) {
            StripeProviderClient client = client(missing);
            operations().forEach((name, op) -> assertThatThrownBy(() -> op.accept(client)).as(name).isInstanceOf(ProviderRejectedException.class));
        }
        assertThat(stub.calls()).isEmpty();
    }

    @Test
    void theExpectedAccountIdMustLookLikeAStripeAccount() {
        assertThat(StripeAccountIdentity.problem(StripeStub.ACCOUNT)).isNull();
        for (String bad : new String[] {null, "", "acct_", "acct_has space", "cus_Synthetic1", "ACCT_Synthetic1", "acct_x;drop", "acct_" + "a".repeat(300)}) {
            assertThat(StripeAccountIdentity.problem(bad)).as(String.valueOf(bad)).isNotNull();
        }
    }

    @Test
    void everyOperationThatCallsStripeChecksTheAccountFirst() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/agafari/com/billing/stripe/StripeProviderClient.java"));
        int checked = 0;
        for (String chunk : source.split("@Override")) {
            int end = chunk.indexOf("\n    }\n");
            String method = end < 0 ? chunk : chunk.substring(0, end);
            if (!method.contains("protected ") || !(method.contains("stripe.v1()") || method.contains("call(\""))) continue;
            int guard = method.indexOf("requireAccount();");
            int firstCall = Math.min(idx(method, "stripe.v1()"), idx(method, "call(\""));
            assertThat(guard).as(method.lines().filter(l -> l.contains("protected ")).findFirst().orElse("?")).isPositive().isLessThan(firstCall);
            checked++;
        }
        assertThat(checked).as("all eight Stripe operations").isEqualTo(8);
    }

    private static int idx(String s, String what) {
        int i = s.indexOf(what);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    // ---- the configured price (G8-C) ----

    private void price(Consumer<Map<String, Object>> change) {
        Map<String, Object> p = StripeFixtures.price(PRICE, 2500, "USD", "month");
        change.accept(p);
        stub.on("GET", "/v1/prices/" + PRICE, c -> StripeStub.ok(StripeFixtures.json(p)));
    }

    @Test
    void theSessionUsesThePriceOnlyAfterCheckingItMatchesTheServersAmountCurrencyAndInterval() {
        StripeFixtures.registerPrice(stub, PRICE, 2500, "USD", "month");
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.createdSession("cs_synthetic_ok")));
        client(StripeStub.ACCOUNT).createSession(request(), "checkout:chk-1");
        assertThat(stub.calls().stream().map(StripeStub.Call::path).toList()).containsExactly("/v1/account", "/v1/prices/" + PRICE, "/v1/checkout/sessions");
    }

    @Test
    void aWrongOrForeignPriceIsRefusedAndNoSessionIsCreated() {
        Map<String, Consumer<Map<String, Object>>> wrong = new LinkedHashMap<>();
        wrong.put("another amount", p -> p.put("unit_amount", 2400L));
        wrong.put("another currency", p -> p.put("currency", "eur"));
        wrong.put("another interval", p -> recurring(p).put("interval", "year"));
        wrong.put("every two months", p -> recurring(p).put("interval_count", 2L));
        wrong.put("metered", p -> recurring(p).put("usage_type", "metered"));
        wrong.put("one-time", p -> { p.put("type", "one_time"); p.remove("recurring"); });
        wrong.put("archived", p -> p.put("active", false));
        wrong.put("live mode", p -> p.put("livemode", true));
        wrong.put("tiered", p -> p.put("billing_scheme", "tiered"));
        wrong.put("tax included (AD-16: tax is off)", p -> p.put("tax_behavior", "inclusive"));
        wrong.put("another id", p -> p.put("id", "price_synthetic_other"));
        stub.on("POST", "/v1/checkout/sessions", c -> StripeStub.ok(StripeFixtures.createdSession("cs_never")));
        StripeProviderClient client = client(StripeStub.ACCOUNT);
        wrong.forEach((name, change) -> {
            price(change);
            assertThatThrownBy(() -> client.createSession(request(), "checkout:chk-1")).as(name).isInstanceOf(ProviderRejectedException.class)
                    .hasMessageContaining("price");
        });
        stub.on("GET", "/v1/prices/" + PRICE, c -> StripeStub.error(404, "invalid_request_error", "resource_missing")); // not in this account
        assertThatThrownBy(() -> client.createSession(request(), "checkout:chk-1")).as("a price of another account").isInstanceOf(ProviderRejectedException.class)
                .hasMessageContaining("price");
        assertThat(stub.calls("POST", "/v1/checkout/sessions")).as("no session for a wrong price").isEmpty();
    }

    @Test
    void aPriceLookupThatDidNotCompleteIsRetryable() {
        stub.on("GET", "/v1/prices/" + PRICE, c -> StripeStub.timeout());
        assertThatThrownBy(() -> client(StripeStub.ACCOUNT).createSession(request(), "checkout:chk-1")).isInstanceOf(ProviderTimeoutException.class);
        assertThat(stub.calls("POST", "/v1/checkout/sessions")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recurring(Map<String, Object> p) {
        return (Map<String, Object>) p.get("recurring");
    }

    // ---- webhook events ----

    @Test
    void aWebhookEventNamingAnotherAccountIsRefusedAndTheOwnAccountOrNoneIsAccepted() {
        var verifier = client(StripeStub.ACCOUNT).signatureVerifier();
        String own = "{\"id\":\"evt_synthetic1\",\"type\":\"invoice.paid\",\"account\":\"" + StripeStub.ACCOUNT + "\",\"data\":{\"object\":{}}}";
        String none = "{\"id\":\"evt_synthetic2\",\"type\":\"invoice.paid\",\"data\":{\"object\":{}}}";
        String foreign = "{\"id\":\"evt_synthetic3\",\"type\":\"invoice.paid\",\"account\":\"" + OTHER_ACCOUNT + "\",\"data\":{\"object\":{}}}";
        assertThat(verifier.identify(own.getBytes(StandardCharsets.UTF_8)).eventId()).isEqualTo("evt_synthetic1");
        assertThat(verifier.identify(none.getBytes(StandardCharsets.UTF_8)).eventId()).isEqualTo("evt_synthetic2");
        assertThatThrownBy(() -> verifier.identify(foreign.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
