package com.agafari.com.jobs;

import com.agafari.com.service.MonetizationLocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AGF SUB-R2-08: the only thing that starts the runner. Exists only with {@code app.jobs.enabled=true} (default false). */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true")
public class JobPoller {

    static final long RETENTION_JOB_LOCK_KEY = 4_400_000_021L;

    private final JobRunner runner;
    private final MonetizationLocks locks;
    private final TransactionTemplate transaction;

    public JobPoller(JobRunner runner, MonetizationLocks locks, PlatformTransactionManager transactions) {
        this.runner = runner;
        this.locks = locks;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-ms:5000}")
    public void poll() {
        try {
            runner.pollOnce();
        } catch (RuntimeException e) {
            log.warn("event=job.poll_failed error={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${app.jobs.retention-cron:0 30 3 * * *}", zone = "UTC")
    public void prune() {
        Integer removed = transaction.execute(s -> locks.tryJob(RETENTION_JOB_LOCK_KEY) ? runner.prune() : 0);
        if (removed != null && removed > 0) log.info("event=job.pruned removed={}", removed);
    }
}
