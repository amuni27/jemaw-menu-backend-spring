package com.agafari.com.billing;

import com.agafari.com.jobs.JobHandler;
import com.agafari.com.service.MonetizationClock;
import org.springframework.stereotype.Component;

/** AGF SUB-R4-14: the hourly and daily reconciliation jobs, one per time bucket, so a bucket runs once. */
public final class ReconciliationJobs {

    public static final String HOURLY_TYPE = "billing.reconcile-hourly";
    public static final String DAILY_TYPE = "billing.reconcile-daily";

    public record Payload(String bucket) {
    }

    private ReconciliationJobs() {
    }

    @Component
    public static class Hourly implements JobHandler<Payload> {

        private final ReconciliationService reconciliation;
        private final MonetizationClock clock;

        public Hourly(ReconciliationService reconciliation, MonetizationClock clock) {
            this.reconciliation = reconciliation;
            this.clock = clock;
        }

        @Override
        public String type() {
            return HOURLY_TYPE;
        }

        @Override
        public Class<Payload> payloadType() {
            return Payload.class;
        }

        @Override
        public void handle(Payload payload) {
            reconciliation.hourly(clock.now());
        }
    }

    @Component
    public static class Daily implements JobHandler<Payload> {

        private final ReconciliationService reconciliation;
        private final MonetizationClock clock;

        public Daily(ReconciliationService reconciliation, MonetizationClock clock) {
            this.reconciliation = reconciliation;
            this.clock = clock;
        }

        @Override
        public String type() {
            return DAILY_TYPE;
        }

        @Override
        public Class<Payload> payloadType() {
            return Payload.class;
        }

        @Override
        public void handle(Payload payload) {
            reconciliation.daily(clock.now());
        }
    }
}
