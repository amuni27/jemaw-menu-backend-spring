package com.agafari.com.billing;

import com.agafari.com.jobs.JobHandler;
import com.agafari.com.service.MonetizationClock;
import org.springframework.stereotype.Component;

/** AGF SUB-R4-12 (R-3): the hourly SLA sweep, one job per hour bucket; the sweep itself is a singleton. */
@Component
public class ExceptionSlaJob implements JobHandler<ExceptionSlaJob.Payload> {

    public static final String TYPE = "billing.exception-sla";

    public record Payload(String bucket) {
    }

    private final ExceptionSla sla;
    private final MonetizationClock clock;

    public ExceptionSlaJob(ExceptionSla sla, MonetizationClock clock) {
        this.sla = sla;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(Payload payload) {
        sla.sweep(clock.now());
    }
}
