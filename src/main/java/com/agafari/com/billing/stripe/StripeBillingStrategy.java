package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentMetadata;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.CheckoutPlan;
import com.agafari.com.billing.port.CheckoutRequest;
import com.agafari.com.billing.port.CheckoutSession;
import com.agafari.com.billing.port.CurrentState;
import com.agafari.com.billing.port.LifecycleCommand;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ScheduledChangeEffects;
import com.agafari.com.billing.port.ScheduledChangeView;
import com.agafari.com.billing.port.VerifiedEvent;
import com.agafari.com.exception.CheckoutRefusedException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R5-02: the US market's Stripe strategy, wrapped by {@code UsBillingStrategy}. It plans <b>Agafari-initiated
 * initial checkouts</b> only:
 * <ul>
 *   <li>no current subscription → {@code NEW};</li>
 *   <li>Free with Ads (an OWNER row) → {@code UPGRADE} replacing it, only after verified payment (PO D1);</li>
 *   <li>a trial → refused until AD-12 (conversion stays disabled);</li>
 *   <li>a paid, manual or other row → refused (plan changes on an existing subscription are R5-04).</li>
 * </ul>
 * The Checkout success and cancel URLs must be usable when a checkout is attempted (PO D7).
 */
public final class StripeBillingStrategy implements BillingStrategy {

    static final String NOT_CONFIGURED = "CHECKOUT_NOT_CONFIGURED";
    static final String CONVERSION_UNAVAILABLE = "CHECKOUT_CONVERSION_UNAVAILABLE";
    static final String PLAN_CHANGE_UNAVAILABLE = "CHECKOUT_PLAN_CHANGE_UNAVAILABLE";

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
        if (client.settings().problem() != null) {
            throw new CheckoutRefusedException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED, "Checkout isn't available right now. Please try again later.");
        }
        if (state == null || state.subscriptionId() == null) return new CheckoutPlan(CheckoutKind.NEW, null, null, null);
        return switch (String.valueOf(state.source())) {
            case "OWNER" -> new CheckoutPlan(CheckoutKind.UPGRADE, null, null, state.subscriptionId()); // PO D1
            case "TRIAL" -> throw new CheckoutRefusedException(HttpStatus.CONFLICT, CONVERSION_UNAVAILABLE, "Converting a trial to a paid plan isn't available yet.");
            default -> throw new CheckoutRefusedException(HttpStatus.CONFLICT, PLAN_CHANGE_UNAVAILABLE, "Changing your current plan isn't available yet.");
        };
    }

    @Override
    public ProviderSessionRequest sessionRequest(CheckoutSession session) {
        if (session.kind() != CheckoutKind.NEW && session.kind() != CheckoutKind.UPGRADE) throw new ProviderRejectedException("Only an initial checkout is created with Stripe");
        if (session.providerPriceRef() == null || session.interval() == null) throw new ProviderRejectedException("The quoted price has no Stripe price");
        return new ProviderSessionRequest(session.id(), session.businessId(), session.expected(), session.expiresAt(), session.interval(), session.providerPriceRef(),
                null, // PO U5: a stored Stripe customer is never passed to a new initial checkout
                Map.of(PaymentMetadata.CHECKOUT_ID, session.id(), PaymentMetadata.BUSINESS_ID, session.businessId()));
    }

    /** Not used by the core: payment outcomes come from the verified re-fetch and the matrix. Renewal events are R5-03. */
    @Override
    public List<LifecycleCommand> interpret(VerifiedEvent event, CurrentState state) {
        throw notAuthorized("event interpretation outside the payment outcome path", "SUB-R5-03");
    }

    @Override
    public ScheduledChangeEffects onScheduleChange(ScheduledChangeView change) {
        throw notAuthorized("subscription schedules", "SUB-R5-04");
    }

    private static UnsupportedOperationException notAuthorized(String operation, String story) {
        return new UnsupportedOperationException("Stripe " + operation + " is not implemented: it belongs to " + story + ", which is not authorized");
    }
}
