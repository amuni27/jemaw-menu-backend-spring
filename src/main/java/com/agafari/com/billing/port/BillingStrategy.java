package com.agafari.com.billing.port;

import com.agafari.com.billing.Market;

import java.util.List;

/** AGF SUB-R4-04 (BD-3, DD §6.1): one per market, resolved by {@code BillingStrategyRegistry}. No side effects. */
public interface BillingStrategy {

    Market market();

    /** AGF SUB-R4-05: the provider's identity for billing_account and checkout_session; never taken from a request. */
    String provider();

    /** AGF SUB-R4-05: the guarded client this market's checkouts use. */
    PaymentProviderClient client();

    CheckoutPlan planCheckout(CheckoutRequest request, CurrentState state);

    ProviderSessionRequest sessionRequest(CheckoutSession session);

    List<LifecycleCommand> interpret(VerifiedEvent event, CurrentState state);

    ScheduledChangeEffects onScheduleChange(ScheduledChangeView change);
}
