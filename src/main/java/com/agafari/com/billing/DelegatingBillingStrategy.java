package com.agafari.com.billing;

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

/** AGF SUB-R4-04: a market's strategy shell. It has no market logic yet, and isn't a bean until a real delegate exists. */
abstract class DelegatingBillingStrategy implements BillingStrategy {

    private final Market market;
    private final BillingStrategy delegate;

    DelegatingBillingStrategy(Market market, BillingStrategy delegate) {
        if (delegate == null || delegate.market() != market) throw new IllegalArgumentException("A shell needs a delegate of its own market");
        this.market = market;
        this.delegate = delegate;
    }

    @Override
    public final Market market() {
        return market;
    }

    @Override
    public final String provider() {
        return delegate.provider();
    }

    @Override
    public final PaymentProviderClient client() {
        return delegate.client();
    }

    @Override
    public final CheckoutPlan planCheckout(CheckoutRequest request, CurrentState state) {
        return delegate.planCheckout(request, state);
    }

    @Override
    public final ProviderSessionRequest sessionRequest(CheckoutSession session) {
        return delegate.sessionRequest(session);
    }

    @Override
    public final List<LifecycleCommand> interpret(VerifiedEvent event, CurrentState state) {
        return delegate.interpret(event, state);
    }

    @Override
    public final ScheduledChangeEffects onScheduleChange(ScheduledChangeView change) {
        return delegate.onScheduleChange(change);
    }
}
