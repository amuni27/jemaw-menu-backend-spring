package com.agafari.com.billing;

import com.agafari.com.jobs.JobQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** AGF SUB-R4-12: the hourly trigger of {@link ExceptionSlaJob}. Exists only with {@code app.jobs.enabled=true} (default false). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class ExceptionSlaCadence {

    private final JobQueue queue;

    public ExceptionSlaCadence(JobQueue queue) {
        this.queue = queue;
    }

    @Scheduled(cron = "${app.jobs.exception-sla-cron:0 15 * * * *}", zone = "UTC")
    public void enqueue() {
        String bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
        queue.enqueue(ExceptionSlaJob.TYPE, bucket, new ExceptionSlaJob.Payload(bucket));
    }
}
