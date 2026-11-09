package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-04: the single writer's trial and grandfathering commands (unit level; repository and locks mocked). */
class SubscriptionLifecycleTrialTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T10:00:00Z");
    private static final Actor ADMIN = new Actor("admin-1");

    private BusinessSubscriptionRepository repository;
    private MonetizationLocks locks;
    private SubscriptionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessSubscriptionRepository.class);
        locks = mock(MonetizationLocks.class);
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lifecycle = new SubscriptionLifecycle(repository, locks, new EffectiveStateEvaluator(false), null, null, null);
    }

    private static BusinessSubscription trial() {
        return BusinessSubscription.trial("b1", 1, CREATED, END, "system");
    }

    @Test
    void startTrialCreatesATrialRowAfterCheckingTheLocks() {
        BusinessSubscription s = lifecycle.startTrial("b1", 3, NOW, END, new Actor("system"));
        assertThat(s.getStatus()).isEqualTo("TRIAL");
        assertThat(s.getSource()).isEqualTo("TRIAL");
        assertThat(s.getPlanCode()).isEqualTo("MODEL_1");
        assertThat(s.getConfigVersion()).isEqualTo(3);
        assertThat(s.getPeriodEnd()).isEqualTo(END);
        assertThat(s.getCreatedAt()).isEqualTo(NOW);
        assertThat(s.getCreatedByUserId()).isEqualTo("system");
        InOrder order = inOrder(locks, repository);
        order.verify(locks).assertHeld("b1");
        order.verify(repository).saveAndFlush(any());
    }

    @Test
    void replaceEndsTheTrialFirstThenCreatesTheManualRow() {
        BusinessSubscription t = trial();
        BusinessSubscription manual = lifecycle.replace(t, NOW, new ManualRecord("MODEL_1", 2, END, NOW), ADMIN);
        assertThat(t.getStatus()).isEqualTo("EXPIRED");
        assertThat(t.getEndedAt()).isEqualTo(NOW);
        assertThat(t.getUpdatedByUserId()).isEqualTo("admin-1");
        assertThat(manual.getSource()).isEqualTo("MANUAL");
        assertThat(manual.getStatus()).isEqualTo("ACTIVE");
        assertThat(manual.getPeriodEnd()).isEqualTo(END);
        ArgumentCaptor<BusinessSubscription> saved = ArgumentCaptor.forClass(BusinessSubscription.class);
        verify(repository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(0)).isSameAs(t);
        assertThat(saved.getAllValues().get(1)).isSameAs(manual);
    }

    @Test
    void replaceKeepsTheEndGivenForAnEffectivelyEndedTrial() {
        BusinessSubscription t = trial();
        Instant realEnd = NOW.minusSeconds(3600);
        lifecycle.replace(t, realEnd, new ManualRecord("MODEL_1", 2, null, NOW), ADMIN);
        assertThat(t.getEndedAt()).isEqualTo(realEnd);
        assertThat(t.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyARunningTrialCanBeReplaced() {
        BusinessSubscription notTrial = BusinessSubscription.manual("b1", "MODEL_1", 1, END, CREATED, "admin");
        assertThatThrownBy(() -> lifecycle.replace(notTrial, NOW, new ManualRecord("MODEL_1", 2, END, NOW), ADMIN))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(notTrial.getStatus()).isEqualTo("ACTIVE");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void trialExpiryNeedsOnlyTheRestaurantLockAndReportsWhetherThisCallExpiredIt() {
        when(repository.expireIfDue("s1", NOW)).thenReturn(1).thenReturn(0);
        assertThat(lifecycle.expireTrialIfDue("b1", "s1", NOW)).isTrue();
        assertThat(lifecycle.expireTrialIfDue("b1", "s1", NOW)).isFalse();
        verify(locks, times(2)).assertRestaurantHeld("b1");
        verify(locks, never()).assertHeld(any());
    }

    @Test
    void aGrandfatheredEndChecksTheLocksAndUsesTheConditionalUpdate() {
        BusinessSubscription s = BusinessSubscription.manual("b1", "MODEL_3", 1, END, CREATED, "admin");
        ReflectionTestUtils.setField(s, "id", "s1");
        ReflectionTestUtils.setField(s, "rowVersion", 4);
        when(repository.endGrandfathered("s1", END, NOW, 4)).thenReturn(1).thenReturn(0);
        assertThat(lifecycle.endGrandfathered(s, END, NOW)).isTrue();
        assertThat(lifecycle.endGrandfathered(s, END, NOW)).isFalse();
        verify(locks, times(2)).assertHeld("b1");
    }

    @Test
    void aTrialCantEndAsGrandfathered() {
        BusinessSubscription t = trial();
        assertThatThrownBy(() -> lifecycle.endGrandfathered(t, END, NOW)).isInstanceOf(IllegalTransitionException.class);
        verify(repository, never()).endGrandfathered(any(), any(), any(), anyInt());
    }

    @Test
    void nothingIsWrittenWithoutTheLocks() {
        doThrow(new IllegalStateException("locks")).when(locks).assertHeld("b1");
        doThrow(new IllegalStateException("locks")).when(locks).assertRestaurantHeld("b1");
        BusinessSubscription t = trial();
        ReflectionTestUtils.setField(t, "rowVersion", 0);
        assertThatThrownBy(() -> lifecycle.startTrial("b1", 1, NOW, END, ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.replace(t, NOW, new ManualRecord("MODEL_1", 1, null, NOW), ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.expireTrialIfDue("b1", "s1", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.endGrandfathered(BusinessSubscription.manual("b1", "MODEL_3", 1, END, CREATED, "a"), END, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(t.getStatus()).isEqualTo("TRIAL");
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).expireIfDue(any(), eq(NOW));
        verify(repository, never()).endGrandfathered(any(), any(), any(), anyInt());
    }
}
