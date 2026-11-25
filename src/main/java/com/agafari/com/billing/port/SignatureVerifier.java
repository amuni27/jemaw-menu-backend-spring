package com.agafari.com.billing.port;

import java.util.Map;
import java.util.Optional;

/** AGF SUB-R4-04: verifies a webhook against its raw body (DD §8.1). */
public interface SignatureVerifier {

    boolean verify(Map<String, String> headers, byte[] rawBody);

    /**
     * AGF SUB-R4-07: the event's identity, read only after {@link #verify} succeeded.
     *
     * @throws IllegalArgumentException if a signed body isn't a well-formed event
     */
    InboundEvent identify(byte[] rawBody);

    /**
     * AGF SUB-R4-10: the provider payment reference a stored event names, used only to choose what to re-fetch; the event
     * body is never evidence. Empty when the event names no payment: such an event is never processed as one.
     */
    default Optional<String> paymentReference(byte[] rawBody) {
        return Optional.empty();
    }

    /**
     * AGF SUB-R5-03 (addendum §4): the object a stored state event (a failed renewal, a subscription change or deletion) asks
     * Agafari to re-fetch. Only what to re-fetch is read; the event body is never evidence. Empty for every other event.
     */
    default Optional<StateTrigger> stateTrigger(byte[] rawBody) {
        return Optional.empty();
    }

    /**
     * AGF BILLING-UNSUPPORTED-EVENTS (register §96 U-1): whether Agafari processes events of this exact type from this
     * provider. A provider that names its handled types lists them, so any other type, including one the provider adds later,
     * is ignored, not retried. A provider that names none is treated as before: every type is processed.
     */
    default boolean handlesEventType(String type) {
        return true;
    }
}
