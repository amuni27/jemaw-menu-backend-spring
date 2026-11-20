package com.agafari.com.billing.port;

/**
 * AGF SUB-R5-03 (addendum §4): what a verified state event asks Agafari to re-fetch. The event decides nothing itself: the
 * re-fetched current state does, never the event's type, order or body.
 */
public record StateTrigger(Kind kind, String ref) {

    public enum Kind { INVOICE, SUBSCRIPTION }

    public StateTrigger {
        if (kind == null || ref == null || ref.isBlank()) throw new IllegalArgumentException("A kind and reference are required");
    }
}
