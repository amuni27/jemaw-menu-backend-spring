package com.agafari.com.billing;

import com.agafari.com.jobs.JobHandler;
import org.springframework.stereotype.Component;

/** AGF SUB-R4-05 (BD-14, DD §7.2): the backstop that recovers a checkout left CREATING. */
@Component
public class CheckoutCreateHandler implements JobHandler<CheckoutCreatePayload> {

    public static final String TYPE = "checkout.create";

    private final CheckoutService checkouts;

    public CheckoutCreateHandler(CheckoutService checkouts) {
        this.checkouts = checkouts;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<CheckoutCreatePayload> payloadType() {
        return CheckoutCreatePayload.class;
    }

    @Override
    public void handle(CheckoutCreatePayload payload) {
        checkouts.recover(payload.checkoutId());
    }
}
