package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;
import com.agafari.com.billing.PriceInterval;

/** AGF SUB-R4-04: what a checkout will be (DD §6.1). */
public record CheckoutPlan(CheckoutKind kind, Money amount, PriceInterval interval, String replacesSubscriptionId) {
}
