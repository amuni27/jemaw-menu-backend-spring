package com.agafari.com.subscription;

import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jpa.entity.ScheduledChange;
import com.agafari.com.jpa.repository.ScheduledChangeRepository;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.ScheduledChangeService;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** AGF SUB-R2-11: persists a cancellation or Free downgrade that reads already treat as applied (key = the change). */
@Component
public class ScheduledChangeJob implements JobHandler<ScheduledChangeJob.Payload> {

    public static final String TYPE = "scheduled-change.apply";
    private static final int BATCH = 200;

    public record Payload(String changeId) {
    }

    private final ScheduledChangeRepository changes;
    private final ScheduledChangeService service;
    private final JobQueue queue;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;

    public ScheduledChangeJob(ScheduledChangeRepository changes, ScheduledChangeService service, JobQueue queue, MonetizationClock clock, PlatformTransactionManager transactions) {
        this.changes = changes;
        this.service = service;
        this.queue = queue;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    public int enqueueDue() {
        int due = 0;
        String after = "";
        List<ScheduledChange> batch;
        do {
            batch = changes.findDue(clock.now(), after, Limit.of(BATCH));
            if (batch.isEmpty()) break;
            after = batch.get(batch.size() - 1).getId();
            due += batch.size();
            List<ScheduledChange> found = batch;
            transaction.executeWithoutResult(status -> found.forEach(c -> queue.enqueue(TYPE, c.getId(), new Payload(c.getId()))));
        } while (batch.size() == BATCH);
        return due;
    }

    @Override
    public void handle(Payload payload) {
        service.apply(payload.changeId());
    }
}
