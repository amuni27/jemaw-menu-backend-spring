package com.agafari.com.billing.port;

/** AGF SUB-R5-02 (D4): a cancellation's definite result. Anything uncertain is a {@link ProviderTimeoutException}. */
public enum SubscriptionCancelResult { CANCELLED, ALREADY_CANCELLED }
