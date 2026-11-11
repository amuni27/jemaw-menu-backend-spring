package com.agafari.com.billing;

import com.agafari.com.jobs.JobHandler;
import org.springframework.stereotype.Component;

/** AGF SUB-R4-13 (DD §10.2): the {@code refund.execute} job; one per refund request, keyed by its id. */
@Component
public class RefundExecuteHandler implements JobHandler<RefundExecuteHandler.Payload> {

    public record Payload(String refundRequestId) {
    }

    private final RefundService refunds;

    public RefundExecuteHandler(RefundService refunds) {
        this.refunds = refunds;
    }

    @Override
    public String type() {
        return RefundService.JOB_TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    /** AGF R4-16-D1: the provider of the verified charge being refunded (the join {@code RefundService} uses). */
    @Override
    public String providerSql() {
        return "(select p.provider from refund_request r join payment p on p.id = r.payment_id where r.id = j.payload->>'refundRequestId')";
    }

    @Override
    public void handle(Payload payload) {
        refunds.execute(payload.refundRequestId());
    }
}
