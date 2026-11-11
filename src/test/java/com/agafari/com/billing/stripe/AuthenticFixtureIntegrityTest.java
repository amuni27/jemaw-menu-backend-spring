package com.agafari.com.billing.stripe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stripe.Stripe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF R5-02 Q6: the imported {@link AuthenticStripeFixtures AUTHENTIC} set stays exactly the set the PO reviewed, and stays
 * safe. This is the guide's security gate (§14.3, §20.7) as a JUnit test, so it fails closed in every build: a pinned
 * manifest, per-file SHA-256, the approved scenario file list, test mode only, no secret or personal-data pattern, events
 * at the pinned API version, and error bodies without free text. A failure here is fixed by re-capturing or re-reviewing,
 * never by loosening this test.
 */
class AuthenticFixtureIntegrityTest {

    /** sha256 of the manifest the PO reviewed (Q6 capture run 1 + supplementary B; set fingerprint b59b6a8e…47bb). */
    static final String MANIFEST_SHA256 = "b029a805193bcf1bdc14860929fbd820e85815bad63d317c088d33caca77ec44";

    /** Guide §20.5: every delivered file of the required scenarios V, P, A, B, R, O, C and the optional X (B2 not captured). */
    static final Set<String> APPROVED = new TreeSet<>(List.of(
            "product_m4", "price_m4_month", "price_m4_year",
            "a_session_create", "a_session_after", "a_invoice_verify", "a_invoice_plain", "a_payment_intent", "a_subscription",
            "a_sessions_by_sub", "a_open_invoices_none", "a_event_session_completed", "a_event_invoice_paid",
            "b_session_create", "b_session_during_3ds", "b_session_after", "b_invoice_verify", "b_invoice_plain", "b_payment_intent",
            "b_subscription", "b_sessions_by_sub", "b_open_invoices_none", "b_event_session_completed", "b_event_invoice_paid",
            "r_clock", "r_customer", "r_pm_attach", "r_customer_default", "r_subscription_create", "r_invoices", "r_invoice_create_verify",
            "r_invoice_cycle_verify", "r_sessions_by_sub", "r_event_invoice_paid_cycle",
            "o_invoice_item", "o_invoice_create", "o_invoice_finalize", "o_open_invoices_other", "o_invoice_void", "o_open_invoices_after_void",
            "c_cancel", "c_cancel_again", "c_subscription_after", "c_invoices_after",
            "x_session_create", "x_session_expire", "x_expire_completed"));

    private static final Map<String, Pattern> FORBIDDEN = Map.of(
            "secret key", Pattern.compile("\\b(sk|rk|pk)_(test|live)_"), "signing secret", Pattern.compile("whsec_"),
            "client secret", Pattern.compile("_secret_"), "auth header", Pattern.compile("(?i)\\b(authorization|bearer|stripe-signature)\\b"),
            "email", Pattern.compile("@"), "url", Pattern.compile("(?i)\\bhttps?:|\\bwww\\."), "account id", Pattern.compile("\\bacct_"),
            "ipv4", Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "card-like number", Pattern.compile("\\b\\d{13,19}\\b"));
    private static final Set<String> MUST_BE_REDACTED = Set.of("email", "customer_email", "receipt_email", "name", "customer_name", "phone",
            "customer_phone", "fingerprint", "last4", "exp_month", "exp_year", "iin", "network_transaction_id", "authorization_code", "client_secret",
            "url", "success_url", "cancel_url", "return_url", "hosted_invoice_url", "invoice_pdf", "receipt_url", "ip", "ip_address", "user_agent",
            "address", "customer_address", "shipping", "shipping_details", "customer_shipping", "billing_details", "customer_details",
            "account_name", "statement_descriptor", "calculated_statement_descriptor", "message", "doc_url", "request_log_url", "description");

    private static Path dir() throws URISyntaxException {
        return Path.of(AuthenticFixtureIntegrityTest.class.getClassLoader().getResource(AuthenticStripeFixtures.DIR + "manifest.json").toURI()).getParent();
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void theManifestIsTheOneThePoReviewed() throws Exception {
        assertThat(sha256(Files.readAllBytes(dir().resolve("manifest.json")))).isEqualTo(MANIFEST_SHA256);
        JsonObject m = AuthenticStripeFixtures.manifest();
        assertThat(m.get("requested_api_version").getAsString()).isEqualTo("2026-10-28.endive");
        assertThat(m.get("environment").getAsString()).isEqualTo("sandbox");
        assertThat(m.getAsJsonObject("stopped").size()).as("no stopped scenario").isZero();
        assertThat(m.getAsJsonArray("blocked").size()).as("nothing blocked").isZero();
    }

    @Test
    void exactlyTheApprovedFilesArePresentAndEachMatchesItsManifestHash() throws Exception {
        Set<String> present;
        try (Stream<Path> files = Files.list(dir())) {
            present = files.map(p -> p.getFileName().toString()).filter(n -> !n.equals("manifest.json")).map(n -> n.replaceAll("\\.json$", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(present).isEqualTo(APPROVED).hasSize(47);
        JsonObject files = AuthenticStripeFixtures.manifest().getAsJsonObject("files");
        Set<String> listed = files.keySet().stream().map(n -> n.replaceAll("\\.json$", "")).collect(Collectors.toCollection(TreeSet::new));
        assertThat(listed).isEqualTo(APPROVED);
        for (String name : APPROVED) {
            JsonObject entry = files.getAsJsonObject(name + ".json");
            assertThat(sha256(Files.readAllBytes(dir().resolve(name + ".json")))).as(name).isEqualTo(entry.get("sha256").getAsString());
            assertThat(entry.get("exit").getAsInt()).as(name + " CLI exit").isZero();
            List<String> argv = new ArrayList<>();
            entry.getAsJsonArray("argv").forEach(a -> argv.add(a.getAsString()));
            assertThat(argv).as(name + ": never live, never headers").doesNotContain("--live", "-s", "--show-headers", "--api-key");
            if (!argv.get(1).startsWith("/v1/events/")) assertThat(argv).as(name + ": pinned API version").containsSequence("-v", "2026-10-28.endive");
        }
    }

    @Test
    void everythingIsTestModeAndNothingSensitiveRemains() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String name : APPROVED) walk(name, AuthenticStripeFixtures.json(name), "$", problems);
        walk("manifest", AuthenticStripeFixtures.manifest(), "$", problems);
        for (String name : APPROVED) {
            String text = new String(Files.readAllBytes(dir().resolve(name + ".json")), StandardCharsets.UTF_8);
            assertThat(text).as(name).doesNotContain("\"livemode\": true", "\"livemode\":true");
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void eventsAreAtThePinnedVersionAndErrorsCarryNoFreeText() {
        for (String name : APPROVED) {
            JsonObject o = AuthenticStripeFixtures.json(name);
            if (o.has("error")) {
                assertThat(o.keySet()).as(name).containsExactly("error");
                assertThat(o.getAsJsonObject("error").keySet()).as(name).isSubsetOf("type", "code", "param", "decline_code");
            } else if ("event".equals(o.get("object").getAsString())) {
                assertThat(o.get("api_version").getAsString()).as(name).isEqualTo(Stripe.API_VERSION).isEqualTo("2026-10-28.endive");
                assertThat(o.get("livemode").getAsBoolean()).as(name).isFalse();
            } else if (!"list".equals(o.get("object").getAsString())) {
                assertThat(o.get("livemode").getAsBoolean()).as(name).isFalse();
            }
        }
    }

    // ---- helpers ----

    private static void walk(String file, JsonElement e, String path, List<String> problems) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) {
                String p = path + "." + f.getKey();
                JsonElement v = f.getValue();
                if (f.getKey().equals("livemode") && !(v.isJsonPrimitive() && !v.getAsBoolean())) problems.add(file + " " + p + ": livemode is not false");
                boolean redacted = v.isJsonNull() || (v.isJsonPrimitive() && v.getAsString().equals("[REDACTED]"));
                if (MUST_BE_REDACTED.contains(f.getKey()) && !redacted && !file.equals("manifest")) problems.add(file + " " + p + ": must be redacted");
                walk(file, v, p, problems);
            }
        } else if (e.isJsonArray()) {
            int i = 0;
            for (JsonElement x : e.getAsJsonArray()) walk(file, x, path + "[" + i++ + "]", problems);
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            FORBIDDEN.forEach((why, rx) -> {
                if (rx.matcher(s).find()) problems.add(file + " " + path + ": looks like " + why);
            });
        }
    }
}
