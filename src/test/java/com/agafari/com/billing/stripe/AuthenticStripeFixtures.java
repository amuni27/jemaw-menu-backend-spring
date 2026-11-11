package com.agafari.com.billing.stripe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGF R5-02 Q6 (tests only). <b>AUTHENTIC fixtures</b>: real responses and events from the Agafari Stripe <b>sandbox</b>,
 * captured on 2026-11-11 by the Product Owner with guide revision 1 (§20), redacted through its retained-field allowlist,
 * passed its security and capture gates, and reviewed by the PO before import (fingerprint {@code b59b6a8e…47bb}).
 * Test mode only; API {@code 2026-10-28.endive}. The files are served <b>byte for byte</b>, unchanged.
 *
 * <p>{@link Rekey} exists only for the database flow tests: it renames every Stripe id and the disposable Agafari fixture
 * UUIDs consistently (the same rename in every file), so each test owns its rows in the shared test database. No other
 * byte changes, so every relationship between the authentic objects is preserved.
 */
public final class AuthenticStripeFixtures {

    public static final String DIR = "stripe/fixtures/2026-10-28.endive/";
    public static final String AUTHENTIC = "AUTHENTIC (Agafari Stripe sandbox, Q6 capture 2026-11-11, redacted)";

    private AuthenticStripeFixtures() {
    }

    public static String raw(String name) {
        String file = name.endsWith(".json") ? name : name + ".json";
        try (InputStream in = AuthenticStripeFixtures.class.getClassLoader().getResourceAsStream(DIR + file)) {
            if (in == null) throw new IllegalStateException("Missing authentic fixture " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonObject json(String name) {
        return JsonParser.parseString(raw(name)).getAsJsonObject();
    }

    public static JsonObject manifest() {
        return json("manifest");
    }

    /** A disposable Agafari id the capture used (CHK_A, BIZ_A, ...), from the manifest. */
    public static String fixtureId(String key) {
        return manifest().getAsJsonObject("fixture_ids").get(key).getAsString();
    }

    /** The value at a dotted path such as {@code status_transitions.paid_at} or {@code lines.data[0].period.start}; null if absent. */
    public static JsonElement at(String name, String path) {
        JsonElement e = json(name);
        for (String part : path.split("\\.")) {
            Matcher m = Pattern.compile("([^\\[]+)(?:\\[(\\d+)])?").matcher(part);
            if (!m.matches() || e == null || !e.isJsonObject()) return null;
            e = e.getAsJsonObject().get(m.group(1));
            if (m.group(2) != null) {
                if (e == null || !e.isJsonArray()) return null;
                JsonArray a = e.getAsJsonArray();
                int i = Integer.parseInt(m.group(2));
                e = i < a.size() ? a.get(i) : null;
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

    public static List<String> ids(String listName) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : json(listName).getAsJsonArray("data")) out.add(e.getAsJsonObject().get("id").getAsString());
        return out;
    }

    /** Consistent renaming of ids across all files (database flow tests only); see the class comment. */
    public static final class Rekey {
        /** A real Stripe id always has a digit or capital letter in its body; keys and enums such as "price_details" never do. */
        private static final Pattern STRIPE_ID = Pattern.compile("\"((?:cs|in|sub|cus|pi|ch|price|prod|evt|inpay|clock|il|si|ii|pm|req)_(?=[A-Za-z0-9_]*[A-Z0-9])[A-Za-z0-9_]+)\"");
        private final String suffix;
        private final Map<String, String> values = new HashMap<>();

        public Rekey(String suffix) {
            if (!suffix.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("suffix");
            this.suffix = suffix;
        }

        /** Replaces a disposable Agafari fixture value (a UUID from the manifest) wherever it appears. */
        public Rekey value(String from, String to) {
            values.put(from, to);
            return this;
        }

        public String id(String stripeId) {
            return stripeId + suffix;
        }

        public String apply(String json) {
            String out = STRIPE_ID.matcher(json).replaceAll(m -> Matcher.quoteReplacement("\"" + m.group(1) + suffix + "\""));
            for (Map.Entry<String, String> v : values.entrySet()) out = out.replace("\"" + v.getKey() + "\"", "\"" + v.getValue() + "\"");
            return out;
        }

        public String fixture(String name) {
            return apply(raw(name));
        }
    }
}
