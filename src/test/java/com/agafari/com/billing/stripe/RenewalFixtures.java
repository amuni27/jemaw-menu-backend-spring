package com.agafari.com.billing.stripe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * AGF SUB-R5-03 (tests only). <b>AUTHENTIC, PARTIAL</b> renewal fixtures: real objects and events from the Agafari Stripe
 * <b>sandbox</b>, scenario RF (renewal fails on a declining card, then recovers on a new card within grace), captured by the
 * Product Owner on 2026-11-20 with the R5-03 capture guide revision 1, redacted by {@code tools/r503.py} (security gate PASS).
 * Test mode only; API {@code 2026-10-28.endive}. The capture is <b>not complete</b> (RX, cleanup and the A5 restoration are
 * pending) and the set still needs the PO's final fixture review; see {@code manifest.json}. Files are served byte for byte.
 */
public final class RenewalFixtures {

    public static final String DIR = "stripe/fixtures/r503-2026-10-28.endive/";
    public static final String AUTHENTIC = "AUTHENTIC (Agafari Stripe sandbox, R5-03 capture 2026-11-20, scenario RF, redacted; partial set)";

    private RenewalFixtures() {
    }

    public static String raw(String name) {
        String file = name.endsWith(".json") ? name : name + ".json";
        try (InputStream in = RenewalFixtures.class.getClassLoader().getResourceAsStream(DIR + file)) {
            if (in == null) throw new IllegalStateException("Missing authentic renewal fixture " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonObject json(String name) {
        return JsonParser.parseString(raw(name)).getAsJsonObject();
    }

    /** A dotted path such as {@code lines.data[0].period.start}; reuses the Q6 reader's path rules. */
    public static JsonElement at(String name, String path) {
        JsonElement e = json(name);
        for (String part : path.split("\\.")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([^\\[]+)(?:\\[(\\d+)])?").matcher(part);
            if (!m.matches() || e == null || !e.isJsonObject()) return null;
            e = e.getAsJsonObject().get(m.group(1));
            if (m.group(2) != null) {
                if (e == null || !e.isJsonArray()) return null;
                int i = Integer.parseInt(m.group(2));
                e = i < e.getAsJsonArray().size() ? e.getAsJsonArray().get(i) : null;
            }
            if (e != null && e.isJsonNull()) return null;
        }
        return e;
    }

    public static String str(String name, String path) {
        JsonElement e = at(name, path);
        return e == null ? null : e.getAsString();
    }

    public static long num(String name, String path) {
        return at(name, path).getAsLong();
    }

    public static Instant epoch(String name, String path) {
        return Instant.ofEpochSecond(num(name, path));
    }

    /**
     * <b>SYNTHETIC</b>: the recovered PaymentIntent with its latest charge expanded. The capture recorded the PaymentIntent only
     * in its failed state (before recovery), so this stands in for the one re-fetch the authentic set lacks. Its ids, amount,
     * currency and customer are the authentic ones; its success fields are synthetic.
     */
    public static String recoveredPaymentIntent() {
        return succeededPaymentIntent(str("rf_invoice_recovered", "payments.data[0].payment.payment_intent"), num("rf_invoice_recovered", "amount_paid"),
                str("rf_invoice_recovered", "customer"));
    }

    /** <b>SYNTHETIC</b>: a succeeded USD card PaymentIntent with its latest charge, for an invoice whose PaymentIntent wasn't captured. */
    public static String succeededPaymentIntent(String pi, long amount, String customer) {
        return "{\"id\":\"" + pi + "\",\"object\":\"payment_intent\",\"amount\":" + amount + ",\"amount_received\":" + amount + ",\"currency\":\"usd\",\"customer\":\"" + customer
                + "\",\"livemode\":false,\"status\":\"succeeded\",\"latest_charge\":{\"id\":\"ch_synthetic_" + pi.substring(3) + "\",\"object\":\"charge\",\"amount\":" + amount
                + ",\"captured\":true,\"paid\":true,\"refunded\":false,\"status\":\"succeeded\",\"livemode\":false,\"payment_method_details\":{\"type\":\"card\"}}}";
    }
}
