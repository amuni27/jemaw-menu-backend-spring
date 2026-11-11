package com.agafari.com.billing.stripe;

import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.SignatureVerifier;
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
 * {@code checkout.session.completed} and {@code invoice.paid} name the first invoice (PO U1, D6); nothing else names a payment.
 */
final class StripeSignatureVerifier implements SignatureVerifier {

    static final String HEADER = "Stripe-Signature";
    static final long TOLERANCE_SECONDS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String secret;
    private final Clock clock;

    StripeSignatureVerifier(String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock;
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

    private static JsonNode parse(byte[] rawBody) {
        try {
            return JSON.readTree(rawBody);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not JSON");
        }
    }
}
