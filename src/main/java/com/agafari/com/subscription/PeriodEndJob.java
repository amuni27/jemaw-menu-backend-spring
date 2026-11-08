package com.agafari.com.subscription;

import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * AGF SUB-R2-10 (TA §4.3 rule 6, DD §3.3): persists what reads already enforce. The cadence enqueues one job per due row
 * (key = subscription + period end); the handler calls {@code materialize}, which re-evaluates the row under the locks, so
 * a repeat, a stale job or an admin extension changes nothing. Whether a row is due is decided only by {@link EffectiveState}.
 */
@Component
public class PeriodEndJob implements JobHandler<PeriodEndJob.Payload> {

    public static final String TYPE = "period-end.persist";
    private static final int BATCH = 200;

    public record Payload(String businessId, String subscriptionId, Instant periodEnd) {
    }

    private final BusinessSubscriptionRepository subscriptions;
    private final EffectiveStateEvaluator evaluator;
    private final MonetizationClock clock;
    private final JobQueue queue;
    private final SubscriptionLifecycle lifecycle;
    private final MonetizationLocks locks;
    private final GrandfatheringService grandfathering;
    private final TransactionTemplate transaction;

    public PeriodEndJob(BusinessSubscriptionRepository subscriptions, EffectiveStateEvaluator evaluator, MonetizationClock clock, JobQueue queue,
                        SubscriptionLifecycle lifecycle, MonetizationLocks locks, GrandfatheringService grandfathering, PlatformTransactionManager transactions) {
        this.subscriptions = subscriptions;
        this.evaluator = evaluator;
        this.clock = clock;
        this.queue = queue;
        this.lifecycle = lifecycle;
        this.locks = locks;
        this.grandfathering = grandfathering;
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

    /** One job per due row, in batches; repeated runs and other instances enqueue the same keys. Returns the rows found due. */
    public int enqueueDue() {
        if (!evaluator.isPeriodEndEnabled()) return 0;
        int due = 0;
        String after = "";
        List<BusinessSubscription> batch;
        do {
            batch = subscriptions.findPeriodEndCandidates(after, Limit.of(BATCH));
            if (batch.isEmpty()) break;
            after = batch.get(batch.size() - 1).getId();
            List<BusinessSubscription> dueRows = batch.stream().filter(this::needsPersisting).toList();
            due += dueRows.size();
            transaction.executeWithoutResult(status -> dueRows.forEach(s ->
                    queue.enqueue(TYPE, s.getId() + "@" + s.getPeriodEnd(), new Payload(s.getBusinessId(), s.getId(), s.getPeriodEnd()))));
        } while (batch.size() == BATCH);
        return due;
    }

    private boolean needsPersisting(BusinessSubscription s) {
        EffectiveView view = evaluator.view(s, false, clock.now());
        return view.status() != LifecycleStatus.of(s.getStatus()) && (view.status() == LifecycleStatus.EXPIRED || view.status() == LifecycleStatus.PAST_DUE);
    }

    @Override
    public void handle(Payload payload) {
        if (!evaluator.isPeriodEndEnabled()) return;
        grandfathering.expireDueOnTouch(payload.businessId()); // its own rule, reason and audit, as on every create path
        transaction.executeWithoutResult(status -> {
            locks.configShared();
            locks.restaurant(payload.businessId());
            lifecycle.materialize(payload.businessId());
        });
    }
}
