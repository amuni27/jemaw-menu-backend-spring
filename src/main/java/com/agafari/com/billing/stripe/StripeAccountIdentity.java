package com.agafari.com.billing.stripe;

import java.util.regex.Pattern;

/**
 * AGF gate 8 (register §89, PO G8-B): the Stripe account the adapter must be talking to, from the non-secret setting
 * {@link #PROPERTY}. Account IDs aren't secrets, so the value may appear in logs and messages; keys never do.
 */
public final class StripeAccountIdentity {

    public static final String PROPERTY = "app.billing.stripe.expected-account-id";
    private static final Pattern ACCOUNT = Pattern.compile("acct_[A-Za-z0-9]{1,64}");

    private StripeAccountIdentity() {
    }

    /** Null when {@code id} looks like a Stripe account ID, otherwise why not. */
    public static String problem(String id) {
        if (id == null || id.isBlank()) return PROPERTY + " is required when Stripe is enabled";
        if (!ACCOUNT.matcher(id).matches()) return PROPERTY + " must be a Stripe account ID (acct_…)";
        return null;
    }

    /** The configured ID, or a startup refusal naming the setting. */
    public static String require(String id) {
        String problem = problem(id);
        if (problem != null) throw new IllegalStateException(problem);
        return id;
    }
}
