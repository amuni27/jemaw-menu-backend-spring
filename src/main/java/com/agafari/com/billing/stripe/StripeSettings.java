package com.agafari.com.billing.stripe;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * AGF SUB-R5-02 (PO D7): the environment's Checkout success and cancel URLs and the origins they may point to. Non-secret
 * configuration ({@code app.billing.stripe.success-url}, {@code cancel-url}, {@code trusted-origins}) with <b>no defaults</b>:
 * no domain is hard-coded. They are validated when a checkout is attempted, not when the adapter is merely present.
 */
public final class StripeSettings {

    private final String successUrl;
    private final String cancelUrl;
    private final List<String> trustedOrigins;

    public StripeSettings(String successUrl, String cancelUrl, String trustedOrigins) {
        this.successUrl = blankToNull(successUrl);
        this.cancelUrl = blankToNull(cancelUrl);
        this.trustedOrigins = trustedOrigins == null ? List.of()
                : Arrays.stream(trustedOrigins.split(",")).map(String::strip).filter(o -> !o.isEmpty()).map(o -> o.toLowerCase(Locale.ROOT)).toList();
    }

    String successUrl() {
        return successUrl;
    }

    String cancelUrl() {
        return cancelUrl;
    }

    /** Null when both URLs are usable; otherwise why not (names the setting, never more). */
    public String problem() {
        if (trustedOrigins.isEmpty()) return "app.billing.stripe.trusted-origins is not configured";
        for (String origin : trustedOrigins) {
            if (originOf(origin) == null || !originOf(origin).equals(origin)) return "app.billing.stripe.trusted-origins contains an entry that is not a bare origin";
        }
        String success = check("app.billing.stripe.success-url", successUrl);
        return success != null ? success : check("app.billing.stripe.cancel-url", cancelUrl);
    }

    private String check(String name, String url) {
        if (url == null) return name + " is not configured";
        String origin = originOf(url);
        if (origin == null) return name + " is not an absolute https URL (http only for localhost)";
        if (!trustedOrigins.contains(origin)) return name + " points outside the trusted origins";
        return null;
    }

    /** {@code scheme://host[:port]} in lower case, or null for anything else (relative, userinfo, other schemes, plain http off localhost). */
    static String originOf(String url) {
        URI uri;
        try {
            uri = new URI(url.replace("{CHECKOUT_SESSION_ID}", "placeholder"));
        } catch (Exception e) {
            return null;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT), host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) return null;
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
