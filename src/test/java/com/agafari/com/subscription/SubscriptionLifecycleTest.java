package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-02: the single writer's first two commands create exactly the rows the existing factories create. */
class SubscriptionLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Instant END = Instant.parse("2026-12-08T10:00:00Z");

    private BusinessSubscriptionRepository repository;
    private MonetizationLocks locks;
    private SubscriptionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessSubscriptionRepository.class);
        locks = mock(MonetizationLocks.class);
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lifecycle = new SubscriptionLifecycle(repository, locks, new EffectiveStateEvaluator(false), null, null);
    }

    @Test
    void startOwnerFreeCreatesAnOpenEndedOwnerRowAfterCheckingTheLocks() {
        BusinessSubscription s = lifecycle.startOwnerFree("b1", "MODEL_2", 3, NOW, new Actor("owner-1"));
        assertThat(s.getBusinessId()).isEqualTo("b1");
        assertThat(s.getPlanCode()).isEqualTo("MODEL_2");
        assertThat(s.getStatus()).isEqualTo("ACTIVE");
        assertThat(s.getSource()).isEqualTo("OWNER");
        assertThat(s.getConfigVersion()).isEqualTo(3);
        assertThat(s.getPeriodEnd()).isNull();
        assertThat(s.getCreatedAt()).isEqualTo(NOW);
        assertThat(s.getCreatedByUserId()).isEqualTo("owner-1");
        InOrder order = inOrder(locks, repository);
        order.verify(locks).assertHeld("b1");
        order.verify(repository).saveAndFlush(any());
    }

    @Test
    void recordManualCreatesAManualRowAfterCheckingTheLocks() {
        BusinessSubscription s = lifecycle.recordManual("b1", new ManualRecord("MODEL_4", 2, END, NOW), new Actor("admin-1"));
        assertThat(s.getPlanCode()).isEqualTo("MODEL_4");
        assertThat(s.getStatus()).isEqualTo("ACTIVE");
        assertThat(s.getSource()).isEqualTo("MANUAL");
        assertThat(s.getConfigVersion()).isEqualTo(2);
        assertThat(s.getPeriodEnd()).isEqualTo(END);
        assertThat(s.getCreatedByUserId()).isEqualTo("admin-1");
        InOrder order = inOrder(locks, repository);
        order.verify(locks).assertHeld("b1");
        order.verify(repository).saveAndFlush(any());
    }

    @Test
    void nothingIsWrittenWithoutTheLocks() {
        doThrow(new IllegalStateException("locks")).when(locks).assertHeld("b1");
        assertThatThrownBy(() -> lifecycle.startOwnerFree("b1", "MODEL_2", 1, NOW, new Actor("o"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.recordManual("b1", new ManualRecord("MODEL_4", 1, null, NOW), new Actor("a"))).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void materializeDoesNothingYet() {
        lifecycle.materialize("b1"); // persists effective expiry from SUB-R2-07
        verifyNoInteractions(repository, locks);
    }
}
