package com.agafari.com.billing.fake;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.CheckoutKind;
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

/** AGF SUB-R4-04: a strategy for tests. Its amount is a fixture, not a price; it holds no market policy. */
public record FakeStrategy(Market market, String provider, PaymentProviderClient client) implements BillingStrategy {

    public FakeStrategy(Market market) {
        this(market, "FAKE", new FakeProviderClient());
    }

    @Override
    public CheckoutPlan planCheckout(CheckoutRequest request, CurrentState state) {
        return new CheckoutPlan(CheckoutKind.NEW, new Money(1000, market.currency()), PriceInterval.MONTH, state.subscriptionId());
    }

    @Override
    public ProviderSessionRequest sessionRequest(CheckoutSession session) {
        return new ProviderSessionRequest(session.id(), session.expected(), session.expiresAt());
    }

    @Override
    public List<LifecycleCommand> interpret(VerifiedEvent event, CurrentState state) {
        return List.of();
    }

    @Override
    public ScheduledChangeEffects onScheduleChange(ScheduledChangeView change) {
        return new ScheduledChangeEffects(market == Market.US);
    }
}
