package com.agafari.com.billing.port;

/** AGF SUB-R4-04: the committed state a strategy decides on; both null when the restaurant has no current subscription. */
public record CurrentState(String subscriptionId, String planCode) {
}
