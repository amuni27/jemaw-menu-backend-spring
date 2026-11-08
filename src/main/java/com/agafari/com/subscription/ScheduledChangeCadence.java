package com.agafari.com.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AGF SUB-R2-11: the hourly trigger of {@link ScheduledChangeJob}. Exists only with {@code app.jobs.enabled=true} (default false). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class ScheduledChangeCadence {

    private final ScheduledChangeJob job;

    public ScheduledChangeCadence(ScheduledChangeJob job) {
        this.job = job;
    }

    @Scheduled(cron = "${app.jobs.scheduled-change-cron:0 10 * * * *}", zone = "UTC")
    public void enqueue() {
        job.enqueueDue();
    }
}
