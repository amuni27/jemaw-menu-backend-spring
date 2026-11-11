package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.Currency;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.RecordType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R5-01 (PO Q5): the Stripe wiring is off by default and needs no credential; when switched on it reads credentials
 * from the operating-system environment only, accepts test-mode keys only, and never shows a value. The OS environment is
 * replaced in each context with synthetic values, assembled at run time so no key-shaped literal is in the source.
 */
class StripeConfigurationTest {

    // synthetic, assembled at run time (never a real credential, never a key-shaped literal in the source)
    private static final String TEST_KEY = "sk_" + "test_" + "SyntheticR501Value0000000001";
    private static final String RESTRICTED_TEST_KEY = "rk_" + "test_" + "SyntheticR501Value0000000002";
    private static final String LIVE_KEY = "sk_" + "live_" + "SyntheticR501Value0000000003";
    private static final String RESTRICTED_LIVE_KEY = "rk_" + "live_" + "SyntheticR501Value0000000004";
    private static final String PUBLISHABLE_KEY = "pk_" + "test_" + "SyntheticR501Value0000000005";
    private static final String WEBHOOK = "whsec_" + "SyntheticR501Webhook0000000001";
    private static final String WEBHOOK_PREVIOUS = "whsec_" + "SyntheticR501Webhook0000000002";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(StripeConfiguration.class);

    private static Consumer<org.springframework.context.ConfigurableApplicationContext> osEnvironment(Map<String, Object> values) {
        return ctx -> ctx.getEnvironment().getPropertySources()
                .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, values));
    }

    private ApplicationContextRunner enabledWith(Map<String, Object> env) {
        return runner.withPropertyValues("app.billing.stripe.enabled=true").withInitializer(osEnvironment(env)::accept);
    }

    private static Map<String, Object> env(String key, String webhook, String previous) {
        Map<String, Object> m = new HashMap<>();
        if (key != null) m.put(StripeCredentials.ENV_SECRET_KEY, key);
        if (webhook != null) m.put(StripeCredentials.ENV_WEBHOOK_SECRET, webhook);
        if (previous != null) m.put(StripeCredentials.ENV_WEBHOOK_SECRET_PREVIOUS, previous);
        return m;
    }

    private static void assertRefusedWithoutShowingValues(Throwable failure, String variable, String... values) {
        assertThat(failure).as("startup refused").isNotNull();
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) all.append(t.getMessage()).append('\n').append(t);
        assertThat(all.toString()).contains(variable);
        for (String v : values) if (v != null) assertThat(all.toString()).as("the value is never shown").doesNotContain(v).doesNotContain(v.substring(8));
    }

    // ---- off by default ----

    @Test
    void offByDefaultNothingExistsAndNoCredentialIsNeeded() {
        runner.withInitializer(osEnvironment(Map.of())::accept).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(StripeCredentials.class).doesNotHaveBean(StripeProviderClient.class).doesNotHaveBean(BillingStrategy.class);
        });
        runner.withPropertyValues("app.billing.stripe.enabled=false").withInitializer(osEnvironment(env(LIVE_KEY, null, null))::accept)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(StripeProviderClient.class));
    }

    // ---- enabled with valid synthetic test-mode credentials ----

    @Test
    void enabledWithTestModeCredentialsWiresTheUsStrategyWithTheDocumentedCapabilities() {
        enabledWith(env(TEST_KEY, WEBHOOK, WEBHOOK_PREVIOUS)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            BillingStrategy strategy = ctx.getBean(BillingStrategy.class);
            assertThat(strategy.market()).isEqualTo(Market.US);
            assertThat(strategy.provider()).isEqualTo("STRIPE");
            assertThat(strategy.getClass().getName()).as("wrapped by the US market shell").isEqualTo("com.agafari.com.billing.UsBillingStrategy");
            PaymentProviderClient client = strategy.client();
            assertThat(client).isInstanceOf(StripeProviderClient.class);
            assertThat(((StripeProviderClient) client).sdkClientReady()).isTrue();
            // realigned by SUB-R5-02 (I1): only the capabilities Stripe documents; refunds stay off until R5-05
            assertThat(client.capabilities()).isEqualTo(new ProviderCapabilities(true, true, false, java.time.Duration.ofHours(24), false, true));
            assertThat(ctx.getBean(StripeCredentials.class).previousWebhookSecret()).isPresent();
        });
        enabledWith(env(RESTRICTED_TEST_KEY, WEBHOOK, null)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(StripeCredentials.class).restrictedKey()).isTrue();
            assertThat(ctx.getBean(StripeCredentials.class).previousWebhookSecret()).isEmpty();
        });
    }

    /**
     * Realigned by SUB-R5-02: refunds (R5-05), listing (R5-06), renewal interpretation (R5-03) and schedules (R5-04) still
     * refuse. With no Checkout URLs configured (PO D7), planning and session creation refuse before any network call.
     */
    @Test
    void operationsOutsideR502RefuseAndCheckoutNeedsItsUrls() {
        enabledWith(env(TEST_KEY, WEBHOOK, null)).run(ctx -> {
            BillingStrategy strategy = ctx.getBean(BillingStrategy.class);
            PaymentProviderClient c = strategy.client();
            List<Runnable> calls = List.of(
                    () -> c.submitRefund("pi", new Money(100, Currency.USD), "k", Map.of()), () -> c.findRefund("pi", "r"),
                    () -> c.listSince(Instant.now(), RecordType.PAYMENT), () -> strategy.interpret(null, null), () -> strategy.onScheduleChange(null));
            for (Runnable call : calls) {
                assertThatThrownBy(call::run).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("not authorized").hasMessageContaining("SUB-R5-0");
            }
            assertThatThrownBy(() -> strategy.planCheckout(new com.agafari.com.billing.port.CheckoutRequest("b", "p"), new com.agafari.com.billing.port.CurrentState(null, null)))
                    .isInstanceOf(com.agafari.com.exception.CheckoutRefusedException.class).hasMessageContaining("isn't available");
            assertThatThrownBy(() -> c.createSession(new com.agafari.com.billing.port.ProviderSessionRequest("c", "b", new Money(100, Currency.USD), Instant.now().plusSeconds(7200),
                    com.agafari.com.billing.PriceInterval.MONTH, "price_x", null, Map.of("agafari_checkout_id", "c", "agafari_business_id", "b")), "k"))
                    .isInstanceOf(com.agafari.com.billing.port.ProviderRejectedException.class).hasMessageContaining("not configured");
            assertThatThrownBy(() -> c.verifyPayment("pi_not_an_invoice")).isInstanceOf(com.agafari.com.billing.port.ProviderRejectedException.class);
        });
    }

    // ---- refusals, never showing a value ----

    @Test
    void aLiveOrNonSecretKeyIsRefused() {
        for (String bad : List.of(LIVE_KEY, RESTRICTED_LIVE_KEY, PUBLISHABLE_KEY, "not-a-stripe-key-value-0001")) {
            enabledWith(env(bad, WEBHOOK, null)).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_SECRET_KEY, bad, WEBHOOK);
                assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("test-mode");
            });
        }
    }

    @Test
    void missingOrMalformedCredentialsAreRefused() {
        enabledWith(env(null, WEBHOOK, null)).run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_SECRET_KEY, WEBHOOK));
        enabledWith(env(TEST_KEY, null, null)).run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_WEBHOOK_SECRET, TEST_KEY));
        String badWebhook = "wrong_" + "SyntheticR501Webhook0000000003";
        enabledWith(env(TEST_KEY, badWebhook, null)).run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_WEBHOOK_SECRET, TEST_KEY, badWebhook));
        enabledWith(env(TEST_KEY, WEBHOOK, badWebhook)).run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_WEBHOOK_SECRET_PREVIOUS, TEST_KEY, WEBHOOK, badWebhook));
        enabledWith(env(" " + TEST_KEY, WEBHOOK, null)).run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_SECRET_KEY, TEST_KEY));
        enabledWith(env("   ", WEBHOOK, null)).run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_SECRET_KEY, WEBHOOK));
    }

    /** Environment-only: the same names given as application properties (not in the OS environment) are ignored. */
    @Test
    void credentialsInApplicationPropertiesAreIgnored() {
        runner.withPropertyValues("app.billing.stripe.enabled=true", StripeCredentials.ENV_SECRET_KEY + "=" + TEST_KEY, StripeCredentials.ENV_WEBHOOK_SECRET + "=" + WEBHOOK)
                .withInitializer(osEnvironment(Map.of())::accept)
                .run(ctx -> assertRefusedWithoutShowingValues(ctx.getStartupFailure(), StripeCredentials.ENV_SECRET_KEY, TEST_KEY, WEBHOOK));
    }

    // ---- redaction ----

    @Test
    void toStringNeverShowsAValue() {
        Map<String, Object> values = env(TEST_KEY, WEBHOOK, WEBHOOK_PREVIOUS);
        StripeCredentials c = StripeCredentials.fromEnvironment(n -> (String) values.get(n));
        String shown = c + " " + new StripeProviderClient(c);
        for (String v : List.of(TEST_KEY, WEBHOOK, WEBHOOK_PREVIOUS)) assertThat(shown).doesNotContain(v).doesNotContain(v.substring(8));
        assertThat(shown).contains("<redacted>").contains("2026-10-28.endive");
    }
}
