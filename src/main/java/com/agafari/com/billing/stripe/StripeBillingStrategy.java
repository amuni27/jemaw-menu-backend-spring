package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.CheckoutPlan;
import com.agafari.com.billing.port.CheckoutRequest;
import com.agafari.com.billing.port.CheckoutSession;
import com.agafari.com.billing.port.CurrentState;
import com.agafari.com.billing.port.LifecycleCommand;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ScheduledChangeEffects;
import com.agafari.com.billing.port.ScheduledChangeView;
import com.agafari.com.billing.port.VerifiedEvent;

import java.util.List;

/**
 * AGF SUB-R5-01: the US market's Stripe strategy, wrapped by {@code UsBillingStrategy}. Scaffolding only: planning a
 * checkout, building a session request and interpreting events belong to R5-02 (with its addendum, PO Q1/Q2) and R5-03/R5-04
 * (PO Q7/F2), none of which is authorized. Each refuses rather than guessing.
 */
public final class StripeBillingStrategy implements BillingStrategy {

    private final StripeProviderClient client;

    StripeBillingStrategy(StripeProviderClient client) {
        this.client = client;
    }

    @Override
    public Market market() {
        return Market.US;
    }

    @Override
    public String provider() {
        return StripeProviderClient.PROVIDER;
    }

    @Override
    public PaymentProviderClient client() {
        return client;
    }

    @Override
    public CheckoutPlan planCheckout(CheckoutRequest request, CurrentState state) {
        throw notAuthorized("checkout planning", "SUB-R5-02");
    }

    @Override
    public ProviderSessionRequest sessionRequest(CheckoutSession session) {
        throw notAuthorized("checkout session requests", "SUB-R5-02");
    }

    @Override
    public List<LifecycleCommand> interpret(VerifiedEvent event, CurrentState state) {
        throw notAuthorized("event interpretation", "SUB-R5-02 and SUB-R5-03");
    }

    @Override
    public ScheduledChangeEffects onScheduleChange(ScheduledChangeView change) {
        throw notAuthorized("subscription schedules", "SUB-R5-04");
    }

    private static UnsupportedOperationException notAuthorized(String operation, String story) {
        return new UnsupportedOperationException("Stripe " + operation + " is not implemented: it belongs to " + story + ", which is not authorized");
    }
}
