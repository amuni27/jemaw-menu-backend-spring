package com.agafari.com.billing.stripe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AGF SUB-R5-02 (tests only). <b>SYNTHETIC fixtures</b>: Stripe objects shaped after Stripe's public API reference for
 * API {@code 2026-10-28.endive} (invoice, invoice payments, PaymentIntent with its latest charge, subscription,
 * Checkout Session, events). <b>They are not captured sandbox recordings</b>; contract tests against PO-captured fixtures
 * are BLOCKED until capture is approved (Q6). Every object is test-mode ({@code livemode=false}) and carries no personal data.
 *
 * <p>A {@link Paid} scenario is one initial checkout payment; tests change its maps to describe each case, then
 * {@link #register} answers the adapter's re-fetches from them.
 */
public final class StripeFixtures {

    public static final String SYNTHETIC = "SYNTHETIC (not a captured sandbox fixture)";
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private StripeFixtures() {
    }

    public static String json(Object o) {
        return GSON.toJson(o);
    }

    static String id(String prefix) {
        return prefix + "_synthetic" + SEQ.incrementAndGet();
    }

    /** One initial checkout payment's provider objects. */
    public static final class Paid {
        public final String checkoutId, businessId, priceRef, sessionRef, invoiceRef, subscriptionRef, customerRef, paymentIntentRef, chargeRef;
        public final long amount;
        public long periodStart, periodEnd, paidAt;
        public final Map<String, Object> invoice, paymentIntent, subscription, session;
        public final List<Map<String, Object>> openInvoices = new ArrayList<>();
        public final List<Map<String, Object>> extraSessions = new ArrayList<>();

        public Paid(String checkoutId, String businessId, String sessionRef, String priceRef, long amount, Instant periodStart, Instant paidAt) {
            this.checkoutId = checkoutId;
            this.businessId = businessId;
            this.sessionRef = sessionRef;
            this.priceRef = priceRef;
            this.amount = amount;
            this.invoiceRef = id("in");
            this.subscriptionRef = id("sub");
            this.customerRef = id("cus");
            this.paymentIntentRef = id("pi");
            this.chargeRef = id("ch");
            this.periodStart = periodStart.getEpochSecond();
            this.periodEnd = periodStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant().getEpochSecond();
            this.paidAt = paidAt.getEpochSecond();
            this.invoice = invoice();
            this.paymentIntent = paymentIntent();
            this.subscription = subscription();
            this.session = session();
        }

        private Map<String, Object> invoice() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", invoiceRef);
            m.put("object", "invoice");
            m.put("livemode", false);
            m.put("status", "paid");
            m.put("billing_reason", "subscription_create");
            m.put("customer", customerRef);
            m.put("currency", "usd");
            m.put("total", amount);
            m.put("amount_due", amount);
            m.put("amount_paid", amount);
            m.put("amount_remaining", 0);
            m.put("amount_overpaid", 0);
            m.put("amount_paid_off_stripe", 0);
            m.put("starting_balance", 0);
            m.put("pre_payment_credit_notes_amount", 0);
            m.put("post_payment_credit_notes_amount", 0);
            m.put("total_taxes", new ArrayList<>());
            m.put("total_discount_amounts", new ArrayList<>());
            m.put("status_transitions", new LinkedHashMap<>(Map.of("paid_at", paidAt)));
            m.put("parent", new LinkedHashMap<>(Map.of("type", "subscription_details",
                    "subscription_details", new LinkedHashMap<>(Map.of("subscription", subscriptionRef, "metadata", new LinkedHashMap<>(Map.of("agafari_business_id", businessId)))))));
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", id("il"));
            line.put("object", "line_item");
            line.put("amount", amount);
            line.put("currency", "usd");
            line.put("quantity", 1);
            line.put("period", new LinkedHashMap<>(Map.of("start", periodStart, "end", periodEnd)));
            line.put("pricing", new LinkedHashMap<>(Map.of("type", "price_details", "price_details", new LinkedHashMap<>(Map.of("price", priceRef, "product", "prod_synthetic")))));
            m.put("lines", list(new ArrayList<>(List.of(line))));
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id("inpay"));
            record.put("object", "invoice_payment");
            record.put("status", "paid");
            record.put("amount_paid", amount);
            record.put("currency", "usd");
            record.put("payment", new LinkedHashMap<>(Map.of("type", "payment_intent", "payment_intent", paymentIntentRef)));
            m.put("payments", list(new ArrayList<>(List.of(record))));
            return m;
        }

        private Map<String, Object> paymentIntent() {
            Map<String, Object> charge = new LinkedHashMap<>();
            charge.put("id", chargeRef);
            charge.put("object", "charge");
            charge.put("status", "succeeded");
            charge.put("paid", true);
            charge.put("captured", true);
            charge.put("refunded", false);
            charge.put("amount", amount);
            charge.put("currency", "usd");
            charge.put("livemode", false);
            charge.put("payment_method_details", new LinkedHashMap<>(Map.of("type", "card")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", paymentIntentRef);
            m.put("object", "payment_intent");
            m.put("status", "succeeded");
            m.put("amount", amount);
            m.put("amount_received", amount);
            m.put("currency", "usd");
            m.put("livemode", false);
            m.put("latest_charge", charge);
            return m;
        }

        private Map<String, Object> subscription() {
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("id", priceRef);
            price.put("object", "price");
            price.put("currency", "usd");
            price.put("unit_amount", amount);
            price.put("active", true);
            price.put("livemode", false);
            price.put("type", "recurring");
            price.put("recurring", new LinkedHashMap<>(Map.of("interval", "month", "interval_count", 1)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id("si"));
            item.put("object", "subscription_item");
            item.put("quantity", 1);
            item.put("current_period_start", periodStart);
            item.put("current_period_end", periodEnd);
            item.put("price", price);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", subscriptionRef);
            m.put("object", "subscription");
            m.put("status", "active");
            m.put("customer", customerRef);
            m.put("livemode", false);
            m.put("metadata", new LinkedHashMap<>(Map.of("agafari_business_id", businessId)));
            m.put("items", list(new ArrayList<>(List.of(item))));
            return m;
        }

        private Map<String, Object> session() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sessionRef);
            m.put("object", "checkout.session");
            m.put("status", "complete");
            m.put("mode", "subscription");
            m.put("payment_status", "paid");
            m.put("invoice", invoiceRef);
            m.put("subscription", subscriptionRef);
            m.put("customer", customerRef);
            m.put("livemode", false);
            m.put("metadata", new LinkedHashMap<>(Map.of("agafari_checkout_id", checkoutId, "agafari_business_id", businessId)));
            return m;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> line() {
            return ((List<Map<String, Object>>) ((Map<String, Object>) invoice.get("lines")).get("data")).get(0);
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> paymentRecords() {
            return (List<Map<String, Object>>) ((Map<String, Object>) invoice.get("payments")).get("data");
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> charge() {
            return (Map<String, Object>) paymentIntent.get("latest_charge");
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> item() {
            return ((List<Map<String, Object>>) ((Map<String, Object>) subscription.get("items")).get("data")).get(0);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> recurring() {
            return (Map<String, Object>) ((Map<String, Object>) item().get("price")).get("recurring");
        }

        public String checkoutCompletedEvent() {
            return event("checkout.session.completed", session);
        }

        public String invoicePaidEvent() {
            return event("invoice.paid", invoice);
        }
    }

    public static Map<String, Object> list(List<Map<String, Object>> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("object", "list");
        m.put("has_more", false);
        m.put("data", data);
        return m;
    }

    public static String event(String type, Map<String, Object> object) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id("evt"));
        e.put("object", "event");
        e.put("api_version", "2026-10-28.endive");
        e.put("livemode", false);
        e.put("type", type);
        e.put("data", Map.of("object", object));
        return json(e);
    }

    public static String createdSession(String sessionRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sessionRef);
        m.put("object", "checkout.session");
        m.put("status", "open");
        m.put("mode", "subscription");
        m.put("livemode", false);
        m.put("url", "https://checkout.stripe.test/c/pay/" + sessionRef);
        return json(m);
    }

    /** Answers the adapter's re-fetches for one scenario (session listing and open invoices are filtered by their parameters). */
    public static void register(StripeStub stub, Paid p) {
        stub.on("GET", "/v1/invoices/" + p.invoiceRef, c -> StripeStub.ok(json(p.invoice)));
        stub.on("GET", "/v1/payment_intents/" + p.paymentIntentRef, c -> StripeStub.ok(json(p.paymentIntent)));
        stub.on("GET", "/v1/subscriptions/" + p.subscriptionRef, c -> StripeStub.ok(json(p.subscription)));
        stub.on("GET", "/v1/checkout/sessions", c -> {
            List<Map<String, Object>> found = new ArrayList<>();
            if (p.subscriptionRef.equals(String.valueOf(c.params().get("subscription")))) {
                found.add(p.session);
                found.addAll(p.extraSessions);
            }
            return StripeStub.ok(json(list(found)));
        });
        stub.on("GET", "/v1/invoices", c -> StripeStub.ok(json(list(p.customerRef.equals(String.valueOf(c.params().get("customer"))) ? p.openInvoices : List.of()))));
    }
}
