package com.agafari.com.billing.stripe;

import java.util.Optional;
import java.util.function.Function;

/**
 * AGF SUB-R5-01 (PO Q5): Stripe credentials, read from the process environment only ({@link #ENV_SECRET_KEY},
 * {@link #ENV_WEBHOOK_SECRET}, {@link #ENV_WEBHOOK_SECRET_PREVIOUS}). Values are validated and never shown: every message
 * names the variable, never its value, and {@link #toString()} is redacted.
 *
 * <p>Sandbox only: while the R5 gates are open, a key that isn't a Stripe test-mode key ({@code sk_test_} secret key or
 * {@code rk_test_} restricted key) is refused.
 */
public final class StripeCredentials {

    public static final String ENV_SECRET_KEY = "STRIPE_SECRET_KEY";
    public static final String ENV_WEBHOOK_SECRET = "STRIPE_WEBHOOK_SECRET";
    public static final String ENV_WEBHOOK_SECRET_PREVIOUS = "STRIPE_WEBHOOK_SECRET_PREVIOUS";

    private static final String REDACTED = "<redacted>";

    private final String secretKey;
    private final String webhookSecret;
    private final String previousWebhookSecret;

    private StripeCredentials(String secretKey, String webhookSecret, String previousWebhookSecret) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.previousWebhookSecret = previousWebhookSecret;
    }

    /**
     * Reads and validates the three variables from {@code environment} (in the application: the operating-system
     * environment and nothing else).
     *
     * @throws IllegalStateException naming the variable, never its value
     */
    public static StripeCredentials fromEnvironment(Function<String, String> environment) {
        String key = required(environment, ENV_SECRET_KEY);
        if (!(key.startsWith("sk_test_") || key.startsWith("rk_test_"))) {
            throw new IllegalStateException(ENV_SECRET_KEY + " must be a Stripe test-mode key (sk_test_ or rk_test_) while the R5 sandbox gates are open; the value is not shown");
        }
        String webhook = webhookSecret(required(environment, ENV_WEBHOOK_SECRET), ENV_WEBHOOK_SECRET);
        String previous = Optional.ofNullable(environment.apply(ENV_WEBHOOK_SECRET_PREVIOUS)).filter(v -> !v.isBlank())
                .map(v -> webhookSecret(v, ENV_WEBHOOK_SECRET_PREVIOUS)).orElse(null);
        return new StripeCredentials(key, webhook, previous);
    }

    private static String required(Function<String, String> environment, String name) {
        String value = environment.apply(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required when Stripe is enabled (app.billing.stripe.enabled); it is read from the environment only");
        if (!value.equals(value.strip())) throw new IllegalStateException(name + " has leading or trailing whitespace; the value is not shown");
        return value;
    }

    private static String webhookSecret(String value, String name) {
        if (!value.startsWith("whsec_")) throw new IllegalStateException(name + " must be a Stripe webhook signing secret (whsec_); the value is not shown");
        return value;
    }

    /** For the adapter only (package-private): the value never leaves {@code billing.stripe}. */
    String secretKey() {
        return secretKey;
    }

    String webhookSecret() {
        return webhookSecret;
    }

    Optional<String> previousWebhookSecret() {
        return Optional.ofNullable(previousWebhookSecret);
    }

    /** Whether the key is a restricted key ({@code rk_test_}); safe to show. */
    public boolean restrictedKey() {
        return secretKey.startsWith("rk_");
    }

    @Override
    public String toString() {
        return "StripeCredentials[secretKey=" + REDACTED + ", webhookSecret=" + REDACTED + ", previousWebhookSecret=" + (previousWebhookSecret == null ? "none" : REDACTED) + "]";
    }
}
