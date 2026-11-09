package com.agafari.com.subscription;

import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AGF PROVIDER grace-end correction: which job identity each transition gets, at the exact instants. Seven days is a fixture (AD-7 is open). */
class PeriodEndJobKeyTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant GRACE_END = END.plus(Duration.ofDays(7));
    private static final Duration NANO = Duration.ofNanos(1);

    private final BusinessSubscriptionRepository repository = mock(BusinessSubscriptionRepository.class);
    private final MonetizationClock clock = mock(MonetizationClock.class);
    private final JobQueue queue = mock(JobQueue.class);
    private final EffectiveStateEvaluator evaluator = new EffectiveStateEvaluator(true);
    private final PeriodEndJob job = new PeriodEndJob(repository, evaluator, clock, queue,
            new SubscriptionLifecycle(repository, mock(MonetizationLocks.class), evaluator, clock, mock(AdminAuditService.class), null),
            mock(MonetizationLocks.class), mock(GrandfatheringService.class), mock(PlatformTransactionManager.class));

    private static BusinessSubscription provider(Integer graceDays) {
        return BusinessSubscription.provider("b1", "MODEL_4", 1, "price", "P1", null, graceDays, CREATED, END, CREATED, "system");
    }

    private static BusinessSubscription pastDue() {
        BusinessSubscription s = provider(7);
        s.markPastDue(END, END, "system");
        return s;
    }

    private void scan(BusinessSubscription row, Instant now) {
        when(repository.findPeriodEndCandidates(eq(""), any(Limit.class))).thenReturn(List.of(row));
        when(clock.now()).thenReturn(now);
        job.enqueueDue();
    }

    private void assertOnlyKey(BusinessSubscription row, String suffix) {
        verify(queue, times(1)).enqueue(PeriodEndJob.TYPE, row.getId() + "@" + END + suffix, new PeriodEndJob.Payload("b1", row.getId(), END));
    }

    private void assertNothingEnqueued() {
        verify(queue, never()).enqueue(anyString(), anyString(), any());
    }

    @Test
    void nothingBeforeTheExactPeriodEnd() {
        scan(provider(7), END.minus(NANO));
        assertNothingEnqueued();
    }

    @Test
    void pastDueFromTheExactPeriodEnd() {
        BusinessSubscription row = provider(7);
        scan(row, END);
        assertOnlyKey(row, "#PAST_DUE");
    }

    @Test
    void stillPastDueJustBeforeTheGraceEnd() {
        BusinessSubscription row = provider(7);
        scan(row, GRACE_END.minus(NANO));
        assertOnlyKey(row, "#PAST_DUE");
    }

    @Test
    void anUnpersistedRowGoesStraightToExpiredAtTheExactGraceEnd() {
        BusinessSubscription row = provider(7);
        scan(row, GRACE_END);
        assertOnlyKey(row, "#EXPIRED");
    }

    @Test
    void aPastDueRowIsNotEnqueuedAgainDuringGrace() {
        scan(pastDue(), GRACE_END.minus(NANO));
        assertNothingEnqueued();
    }

    @Test
    void aPastDueRowIsExpiredFromTheExactGraceEnd() {
        BusinessSubscription row = pastDue();
        scan(row, GRACE_END);
        assertOnlyKey(row, "#EXPIRED");
    }

    @Test
    void noRecordedGraceMeansExpiredAtThePeriodEnd() {
        BusinessSubscription row = provider(null);
        scan(row, END);
        assertOnlyKey(row, "#EXPIRED");
    }

    @Test
    void theRowsOwnGraceDecidesTheBoundary() {
        BusinessSubscription row = provider(3);
        scan(row, END.plus(Duration.ofDays(3)));
        assertOnlyKey(row, "#EXPIRED");
    }

    @Test
    void oneEvaluationDecidesBothWhetherARowIsDueAndItsKey() {
        BusinessSubscription row = provider(7);
        when(repository.findPeriodEndCandidates(eq(""), any(Limit.class))).thenReturn(List.of(row));
        when(clock.now()).thenReturn(GRACE_END.minus(NANO), GRACE_END);
        job.enqueueDue();
        assertOnlyKey(row, "#PAST_DUE");
        verify(clock, times(1)).now();
    }

    @Test
    void aManualRowsKeyIsByteIdentical() {
        BusinessSubscription row = BusinessSubscription.manual("b1", "MODEL_4", 1, END, CREATED, "a");
        scan(row, END);
        verify(queue, times(1)).enqueue(PeriodEndJob.TYPE, row.getId() + "@2026-06-01T12:00:00Z", new PeriodEndJob.Payload("b1", row.getId(), END));
    }
}
