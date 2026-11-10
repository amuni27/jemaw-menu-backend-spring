package com.agafari.com.billing;

import com.agafari.com.jobs.JobQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** AGF SUB-R4-14: the hourly and daily triggers of {@link ReconciliationJobs}. Exist only with {@code app.jobs.enabled=true} (default false). */
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

    @Scheduled(cron = "${app.jobs.reconcile-daily-cron:0 40 3 * * *}", zone = "UTC")
    public void daily() {
        String bucket = Instant.now().truncatedTo(ChronoUnit.DAYS).toString();
        queue.enqueue(ReconciliationJobs.DAILY_TYPE, bucket, new ReconciliationJobs.Payload(bucket));
    }
}
