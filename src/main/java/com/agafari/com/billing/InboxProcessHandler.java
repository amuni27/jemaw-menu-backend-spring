package com.agafari.com.billing;

import com.agafari.com.billing.webhook.InboxProcessPayload;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.JobHandler;
import org.springframework.stereotype.Component;

/**
 * AGF SUB-R4-10 (DD §8.2): the {@code inbox.process} job. The only caller of {@link PaymentOutcomeService}. It runs outside
 * any transaction; a transient failure (provider timeout, unresolved payment) is retried, anything else leaves the event
 * {@code PENDING} and visible.
 */
@Component
public class InboxProcessHandler implements JobHandler<InboxProcessPayload> {

    private final PaymentOutcomeService outcomes;

    public InboxProcessHandler(PaymentOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @Override
    public String type() {
        return InboxService.JOB_TYPE;
    }

    @Override
    public Class<InboxProcessPayload> payloadType() {
        return InboxProcessPayload.class;
    }

    @Override
    public void handle(InboxProcessPayload payload) {
        outcomes.process(payload.providerEventId());
    }
}
