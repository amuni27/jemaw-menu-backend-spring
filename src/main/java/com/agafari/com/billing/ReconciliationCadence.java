package com.agafari.com.billing;

import com.agafari.com.jobs.JobQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** AGF SUB-R4-14: the hourly and daily triggers of {@link ReconciliationJobs} (and, R5-03, of the {@link RenewalSweep}). Exist only with {@code app.jobs.enabled=true} (default false). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class ReconciliationCadence {

    private final JobQueue queue;

    public ReconciliationCadence(JobQueue queue) {
        this.queue = queue;
    }

    @Scheduled(cron = "${app.jobs.reconcile-hourly-cron:0 25 * * * *}", zone = "UTC")
    public void hourly() {
        String bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
        queue.enqueue(ReconciliationJobs.HOURLY_TYPE, bucket, new ReconciliationJobs.Payload(bucket));
    }

    /** AGF SUB-R5-03 (P-R1): plans the renewal sweep's checks once per hour. */
    @Scheduled(cron = "${app.jobs.renewal-sweep-cron:0 10 * * * *}", zone = "UTC")
    public void renewalSweep() {
        String bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
        queue.enqueue(RenewalSweep.PLAN_TYPE, bucket, new RenewalSweep.PlanPayload(bucket));
    }

    /** AGF SUB-R5-03-D: plans the renewal notices once per hour. */
    @Scheduled(cron = "${app.jobs.renewal-notices-cron:0 5 * * * *}", zone = "UTC")
    public void renewalNotices() {
        String bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
        queue.enqueue(RenewalNotices.PLAN_TYPE, bucket, new RenewalNotices.PlanPayload(bucket));
    }

    @Scheduled(cron = "${app.jobs.reconcile-daily-cron:0 40 3 * * *}", zone = "UTC")
    public void daily() {
        String bucket = Instant.now().truncatedTo(ChronoUnit.DAYS).toString();
        queue.enqueue(ReconciliationJobs.DAILY_TYPE, bucket, new ReconciliationJobs.Payload(bucket));
    }
}
