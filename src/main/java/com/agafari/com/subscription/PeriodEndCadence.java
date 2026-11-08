package com.agafari.com.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AGF SUB-R2-10: the hourly trigger of {@link PeriodEndJob}. Exists only with {@code app.jobs.enabled=true} (default false). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class PeriodEndCadence {

    private final PeriodEndJob job;

    public PeriodEndCadence(PeriodEndJob job) {
        this.job = job;
    }

    @Scheduled(cron = "${app.jobs.period-end-cron:0 5 * * * *}", zone = "UTC")
    public void enqueue() {
        job.enqueueDue();
    }
}
