package com.agafari.com.billing.port;

/** AGF SUB-R4-07: a webhook's identity at its provider. An event id is never a payment, checkout or refund reference (PO D4). */
public record InboundEvent(String eventId, String type) {

    public InboundEvent {
        if (eventId == null || eventId.isBlank() || eventId.length() > 255) throw new IllegalArgumentException("An event id of up to 255 characters is required");
        if (type == null || type.isBlank() || type.length() > 128) throw new IllegalArgumentException("An event type of up to 128 characters is required");
    }
}
