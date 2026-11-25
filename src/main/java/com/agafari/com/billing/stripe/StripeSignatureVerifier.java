package com.agafari.com.billing.stripe;

import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.StateTrigger;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * AGF SUB-R5-02: {@code Stripe-Signature} verification with one signing secret (rotation pairs two of these in the existing
 * {@code RotatingSignatureVerifier}). The SDK's routine checks the HMAC over the raw body, every {@code v1} entry and a
 * 300-second timestamp tolerance. After verification the body only names the event and <b>what to re-fetch</b>: both
 * {@code checkout.session.completed} and {@code invoice.paid} name an invoice (PO U1, D6; a renewal's in R5-03); nothing else
 * names a payment. R5-03's state events name the invoice or subscription whose current state is re-fetched.
 */
final class StripeSignatureVerifier implements SignatureVerifier {

    static final String HEADER = "Stripe-Signature";
    static final long TOLERANCE_SECONDS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String secret;
    private final Clock clock;

    private final String expectedAccountId;

    StripeSignatureVerifier(String secret, Clock clock) {
        this(secret, clock, null);
    }

    /** @param expectedAccountId AGF gate 8: an event that names another account (a Connect event) is refused */
    StripeSignatureVerifier(String secret, Clock clock, String expectedAccountId) {
        this.secret = secret;
        this.clock = clock;
        this.expectedAccountId = expectedAccountId;
    }

    @Override
    public boolean verify(Map<String, String> headers, byte[] rawBody) {
        String header = headers.get(HEADER);
        if (header == null || header.isBlank() || rawBody == null) return false;
        try {
            return Webhook.Signature.verifyHeader(new String(rawBody, StandardCharsets.UTF_8), header, secret, TOLERANCE_SECONDS, clock);
        } catch (SignatureVerificationException e) {
            return false;
        }
    }

    @Override
    public InboundEvent identify(byte[] rawBody) {
        JsonNode event = parse(rawBody);
        String id = event.path("id").asString(""), type = event.path("type").asString("");
        if (!id.startsWith("evt_") || type.isBlank()) throw new IllegalArgumentException("Not a Stripe event");
        String account = event.path("account").asString("");
        if (!account.isEmpty() && !account.equals(expectedAccountId)) throw new IllegalArgumentException("An event of another Stripe account"); // AGF gate 8
        return new InboundEvent(id, type);
    }

    @Override
    public Optional<String> paymentReference(byte[] rawBody) {
        JsonNode event = parse(rawBody);
        JsonNode object = event.path("data").path("object");
        String ref = switch (event.path("type").asString("")) {
            case "checkout.session.completed" -> object.path("invoice").asString("");
            case "invoice.paid" -> object.path("id").asString("");
            default -> "";
        };
        return ref.startsWith("in_") ? Optional.of(ref) : Optional.empty();
    }

    /**
     * AGF SUB-R5-03 (addendum §4): a failed renewal names its invoice, a subscription change or deletion names its
     * subscription. Only the identifier is read, to choose what to re-fetch.
     */
    @Override
    public Optional<StateTrigger> stateTrigger(byte[] rawBody) {
        JsonNode event = parse(rawBody);
        String ref = event.path("data").path("object").path("id").asString("");
        return switch (event.path("type").asString("")) {
            case "invoice.payment_failed" -> ref.startsWith("in_") ? Optional.of(new StateTrigger(StateTrigger.Kind.INVOICE, ref)) : Optional.empty();
            case "customer.subscription.updated", "customer.subscription.deleted" ->
                    ref.startsWith("sub_") ? Optional.of(new StateTrigger(StateTrigger.Kind.SUBSCRIPTION, ref)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * AGF BILLING-UNSUPPORTED-EVENTS (register §96 U-1): the event types Agafari processes, exactly: two payment events
     * ({@link #paymentReference}) and three state events ({@link #stateTrigger}). Any other type is kept and ignored.
     */
    public static final java.util.Set<String> HANDLED_EVENT_TYPES = java.util.Set.of("checkout.session.completed", "invoice.paid", "invoice.payment_failed",
            "customer.subscription.updated", "customer.subscription.deleted");

    @Override
    public boolean handlesEventType(String type) {
        return type != null && HANDLED_EVENT_TYPES.contains(type);
    }

    private static JsonNode parse(byte[] rawBody) {
        try {
            return JSON.readTree(rawBody);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not JSON");
        }
    }
}
