package com.agafari.com.billing;

import com.agafari.com.jobs.JobHandler;
import org.springframework.stereotype.Component;

/** AGF SUB-R4-06 (DD §7.3): asks the provider to expire a cancelled checkout; retries until {@code expires_at}. */
@Component
public class CheckoutExpireHandler implements JobHandler<CheckoutExpirePayload> {

    public static final String TYPE = "checkout.expire";

    private final CheckoutService checkouts;

    public CheckoutExpireHandler(CheckoutService checkouts) {
        this.checkouts = checkouts;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<CheckoutExpirePayload> payloadType() {
        return CheckoutExpirePayload.class;
    }

    @Override
    public void handle(CheckoutExpirePayload payload) {
        checkouts.expire(payload.checkoutId());
    }
}
