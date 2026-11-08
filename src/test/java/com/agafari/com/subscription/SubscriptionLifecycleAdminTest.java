package com.agafari.com.subscription;

import com.agafari.com.exception.ProviderPeriodManagedByBillingException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-03: the single writer's Super Admin commands (unit level; the repository and locks are mocked). */
class SubscriptionLifecycleAdminTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Instant END = Instant.parse("2026-12-08T10:00:00Z");
    private static final Instant LATER = Instant.parse("2027-01-08T10:00:00Z");
    private static final Actor ADMIN = new Actor("admin-1");

    private BusinessSubscriptionRepository repository;
    private MonetizationLocks locks;
    private SubscriptionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessSubscriptionRepository.class);
        locks = mock(MonetizationLocks.class);
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lifecycle = new SubscriptionLifecycle(repository, locks);
    }

    private static BusinessSubscription manual() {
        return BusinessSubscription.manual("b1", "MODEL_4", 1, END, CREATED, "creator");
    }

    private static BusinessSubscription provider() {
        BusinessSubscription s = manual();
        ReflectionTestUtils.setField(s, "source", "PROVIDER");
        return s;
    }

    @Test
    void aPeriodChangeChecksTheLocksThenSavesOnce() {
        BusinessSubscription saved = lifecycle.applyAdminChange(manual(), new AdminPeriodChange(LATER, false, false, NOW), ADMIN);
        assertThat(saved.getPeriodEnd()).isEqualTo(LATER);
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedByUserId()).isEqualTo("admin-1");
        InOrder order = inOrder(locks, repository);
        order.verify(locks).assertHeld("b1");
        order.verify(repository).saveAndFlush(any());
    }

    @Test
    void aCombinedChangeIsSavedExactlyOnce() {
        BusinessSubscription s = manual();
        s.changeTransitionDate(LATER, CREATED, "creator");
        BusinessSubscription saved = lifecycle.applyAdminChange(s, new AdminPeriodChange(LATER, true, true, NOW), ADMIN);
        assertThat(saved.getTransitionDate()).isNull();
        assertThat(saved.getPeriodEnd()).isEqualTo(LATER);
        assertThat(saved.getStatus()).isEqualTo("EXPIRED");
        assertThat(saved.getEndedAt()).isEqualTo(NOW);
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void anEndOnlyChangeLeavesThePeriodAlone() {
        BusinessSubscription saved = lifecycle.applyAdminChange(manual(), new AdminPeriodChange(null, false, true, NOW), ADMIN);
        assertThat(saved.getPeriodEnd()).isEqualTo(END);
        assertThat(saved.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void trialAndOwnerPeriodsCanBeChanged() {
        BusinessSubscription trial = BusinessSubscription.trial("b1", 1, CREATED, END, "system");
        assertThat(lifecycle.applyAdminChange(trial, new AdminPeriodChange(NOW.plusSeconds(60), false, false, NOW), ADMIN).getPeriodEnd())
                .isEqualTo(NOW.plusSeconds(60));
        BusinessSubscription owner = BusinessSubscription.owner("b1", "MODEL_2", 1, CREATED, "owner");
        assertThat(lifecycle.applyAdminChange(owner, new AdminPeriodChange(LATER, false, false, NOW), ADMIN).getPeriodEnd()).isEqualTo(LATER);
    }

    @Test
    void aProviderPeriodIsRefusedAndNothingIsWritten() {
        BusinessSubscription s = provider();
        assertThatThrownBy(() -> lifecycle.requireAdminPeriodEditable(s)).isInstanceOf(ProviderPeriodManagedByBillingException.class);
        assertThatThrownBy(() -> lifecycle.applyAdminChange(s, new AdminPeriodChange(LATER, false, false, NOW), ADMIN))
                .isInstanceOf(ProviderPeriodManagedByBillingException.class);
        assertThat(s.getPeriodEnd()).isEqualTo(END);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void otherSourcesAreEditable() {
        lifecycle.requireAdminPeriodEditable(manual());
        lifecycle.requireAdminPeriodEditable(BusinessSubscription.owner("b1", "MODEL_2", 1, CREATED, "owner"));
        lifecycle.requireAdminPeriodEditable(BusinessSubscription.trial("b1", 1, CREATED, END, "system"));
    }

    @Test
    void aProviderSubscriptionCanStillBeEnded() {
        BusinessSubscription saved = lifecycle.applyAdminChange(provider(), new AdminPeriodChange(null, false, true, NOW), ADMIN);
        assertThat(saved.getStatus()).isEqualTo("EXPIRED");
        assertThat(saved.getPeriodEnd()).isEqualTo(END);
    }

    @Test
    void aTransitionDateCanBeSetAndCleared() {
        BusinessSubscription s = manual();
        assertThat(lifecycle.changeTransitionDate(s, LATER, NOW, ADMIN).getTransitionDate()).isEqualTo(LATER);
        assertThat(lifecycle.changeTransitionDate(s, null, NOW, ADMIN).getTransitionDate()).isNull();
        verify(locks, times(2)).assertHeld("b1");
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test
    void nothingChangesWithoutTheLocks() {
        doThrow(new IllegalStateException("locks")).when(locks).assertHeld("b1");
        BusinessSubscription s = manual();
        assertThatThrownBy(() -> lifecycle.applyAdminChange(s, new AdminPeriodChange(LATER, true, true, NOW), ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.changeTransitionDate(s, LATER, NOW, ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThat(s.getPeriodEnd()).isEqualTo(END);
        assertThat(s.getStatus()).isEqualTo("ACTIVE");
        assertThat(s.getTransitionDate()).isNull();
        verify(repository, never()).saveAndFlush(any());
    }
}
