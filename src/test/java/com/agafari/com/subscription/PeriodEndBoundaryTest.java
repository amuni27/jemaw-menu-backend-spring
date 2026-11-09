package com.agafari.com.subscription;

import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-10: enqueueing and persisting at the exact instant of the period end. */
class PeriodEndBoundaryTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-06-01T12:00:00Z");
    private static final Duration NANO = Duration.ofNanos(1);

    private final BusinessSubscriptionRepository repository = mock(BusinessSubscriptionRepository.class);
    private final MonetizationClock clock = mock(MonetizationClock.class);
    private final JobQueue queue = mock(JobQueue.class);
    private final EffectiveStateEvaluator evaluator = new EffectiveStateEvaluator(true);
    private final BusinessSubscription row = BusinessSubscription.manual("b1", "MODEL_4", 1, END, CREATED, "a");
    private PeriodEndJob job;
    private SubscriptionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new SubscriptionLifecycle(repository, mock(MonetizationLocks.class), evaluator, clock, mock(AdminAuditService.class), null);
        job = new PeriodEndJob(repository, evaluator, clock, queue, lifecycle, mock(MonetizationLocks.class), mock(GrandfatheringService.class), mock(PlatformTransactionManager.class));
        when(repository.findPeriodEndCandidates(eq(""), any(Limit.class))).thenReturn(List.of(row));
        when(repository.findCurrentByBusinessId("b1")).thenReturn(Optional.of(row));
    }

    @Test
    void aRowIsNotEnqueuedBeforeItsExactEnd() {
        when(clock.now()).thenReturn(END.minus(NANO));
        assertThat(job.enqueueDue()).isZero();
        verify(queue, never()).enqueue(anyString(), anyString(), any());
    }

    @Test
    void aRowIsEnqueuedAtItsExactEndWithTheCatalogueKey() {
        when(clock.now()).thenReturn(END);
        assertThat(job.enqueueDue()).isEqualTo(1);
        verify(queue, times(1)).enqueue(PeriodEndJob.TYPE, row.getId() + "@" + END, new PeriodEndJob.Payload("b1", row.getId(), END));
    }

    @Test
    void everyBatchIsScanned() {
        when(clock.now()).thenReturn(END);
        when(repository.findPeriodEndCandidates(eq(""), any(Limit.class))).thenReturn(Collections.nCopies(200, row));
        when(repository.findPeriodEndCandidates(eq(row.getId()), any(Limit.class))).thenReturn(List.of(row));
        assertThat(job.enqueueDue()).isEqualTo(201);
    }

    @Test
    void nothingIsEnqueuedWithTheFlagOff() {
        evaluator.setPeriodEndEnabled(false);
        when(clock.now()).thenReturn(END.plus(Duration.ofDays(1)));
        assertThat(job.enqueueDue()).isZero();
        verify(queue, never()).enqueue(anyString(), anyString(), any());
    }

    @Test
    void materializeWritesNothingBeforeTheExactEnd() {
        when(clock.now()).thenReturn(END.minus(NANO));
        lifecycle.materialize("b1");
        verify(repository, never()).saveAndFlush(any());
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void materializeEndsTheRowAtExactlyItsEnd() {
        when(clock.now()).thenReturn(END);
        lifecycle.materialize("b1");
        verify(repository).saveAndFlush(row);
        assertThat(row.getStatus()).isEqualTo("EXPIRED");
        assertThat(row.getEndedAt()).isEqualTo(END);
        assertThat(row.getEndedReason()).isEqualTo("PERIOD_END");
    }
}
