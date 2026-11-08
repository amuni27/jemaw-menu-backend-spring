package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.ScheduledChange;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.service.Entitlements;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-11 (NC-08): a change counts only from the exact instant of {@code effective_at}. */
class ScheduledChangeBoundaryTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-06-01T12:00:00Z");

    private final BusinessSubscriptionRepository subscriptions = mock(BusinessSubscriptionRepository.class);
    private final MonetizationClock clock = mock(MonetizationClock.class);
    private final BusinessSubscription row = BusinessSubscription.manual("b1", "MODEL_4", 1, END, CREATED, "a");
    private EntitlementService service;

    private static SubscriptionPlan plan(String code, String ad) {
        SubscriptionPlan p = mock(SubscriptionPlan.class);
        when(p.getCode()).thenReturn(code);
        when(p.getAdSource()).thenReturn(ad);
        return p;
    }

    @BeforeEach
    void setUp() {
        MonetizationConfigRepository configs = mock(MonetizationConfigRepository.class);
        MonetizationConfig config = mock(MonetizationConfig.class);
        when(config.models()).thenReturn(java.util.List.of(com.agafari.com.enums.MonetizationModel.MODEL_2));
        when(configs.findTopByOrderByVersionDesc()).thenReturn(Optional.of(config));
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        SubscriptionPlan paid = plan("MODEL_4", "NONE");
        SubscriptionPlan free = plan("MODEL_2", "AGAFARI");
        when(plans.findById("MODEL_4")).thenReturn(Optional.of(paid));
        when(plans.findById("MODEL_2")).thenReturn(Optional.of(free));
        service = new EntitlementService(configs, subscriptions, plans, clock, true, mock(GrandfatheringService.class), new EffectiveStateEvaluator(false));
        when(subscriptions.findCurrentByBusinessId("b1")).thenReturn(Optional.of(row));
    }

    private void due(String kind, String target, Instant now) {
        ScheduledChange c = new ScheduledChange();
        ReflectionTestUtils.setField(c, "kind", kind);
        ReflectionTestUtils.setField(c, "targetPlanCode", target);
        ReflectionTestUtils.setField(c, "effectiveAt", END);
        when(clock.now()).thenReturn(now);
        when(subscriptions.findDueScheduledChange(eq(row.getId()), any())).thenAnswer(i -> !((Instant) i.getArgument(1)).isBefore(END) ? Optional.of(c) : Optional.empty());
    }

    @Test
    void aDowngradeCountsFromExactlyItsTime() {
        due("DOWNGRADE", "MODEL_2", END.minus(Duration.ofNanos(1)));
        assertThat(service.forBusiness("b1").planCode()).isEqualTo("MODEL_4");
        when(clock.now()).thenReturn(END);
        Entitlements e = service.forBusiness("b1");
        assertThat(e.planCode()).isEqualTo("MODEL_2");
        assertThat(e.source()).isEqualTo("OWNER");
    }

    @Test
    void aCancelCountsFromExactlyItsTime() {
        due("CANCEL", null, END.minus(Duration.ofNanos(1)));
        assertThat(service.forBusiness("b1").planCode()).isEqualTo("MODEL_4");
        when(clock.now()).thenReturn(END);
        assertThat(service.forBusiness("b1")).isEqualTo(Entitlements.unrestricted());
    }
}
