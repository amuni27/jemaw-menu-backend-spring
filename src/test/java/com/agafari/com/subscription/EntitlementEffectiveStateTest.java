package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-07: entitlements follow the effective state, to the exact instant. Seven days of grace is a fixture (AD-7 is open). */
class EntitlementEffectiveStateTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-06-01T12:00:00Z");
    private static final Duration NANO = Duration.ofNanos(1);
    private static final Duration GRACE = Duration.ofDays(7);

    private final BusinessSubscriptionRepository subscriptions = mock(BusinessSubscriptionRepository.class);
    private final MonetizationClock clock = mock(MonetizationClock.class);
    private final GrandfatheringService grandfathering = mock(GrandfatheringService.class);
    private final EffectiveStateEvaluator evaluator = new EffectiveStateEvaluator(true);
    private EntitlementService service;

    @BeforeEach
    void setUp() {
        MonetizationConfigRepository configs = mock(MonetizationConfigRepository.class);
        when(configs.findTopByOrderByVersionDesc()).thenReturn(Optional.of(mock(MonetizationConfig.class)));
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        when(plan.getCode()).thenReturn("MODEL_4");
        when(plan.getAdSource()).thenReturn("NONE");
        when(plans.findById(any())).thenReturn(Optional.of(plan));
        service = new EntitlementService(configs, subscriptions, plans, clock, true, grandfathering, evaluator);
    }

    private Entitlements at(BusinessSubscription s, Instant now) {
        when(subscriptions.findCurrentByBusinessId("b1")).thenReturn(Optional.of(s));
        when(clock.now()).thenReturn(now);
        return service.forBusiness("b1");
    }

    private static void assertEntitled(Entitlements e, String status) {
        assertThat(e.mode()).isEqualTo(Entitlements.Mode.ACTIVE);
        assertThat(e.planCode()).isEqualTo("MODEL_4");
        assertThat(e.status()).isEqualTo(status);
    }

    /** Not entitled means today's launch default; never a substituted plan (no automatic Free with Ads). */
    private static void assertNotEntitled(Entitlements e) {
        assertThat(e).isEqualTo(Entitlements.unrestricted());
    }

    private static BusinessSubscription manual(Instant end) {
        return BusinessSubscription.manual("b1", "MODEL_4", 1, end, CREATED, "a");
    }

    private static BusinessSubscription owner(Instant end) {
        BusinessSubscription s = BusinessSubscription.owner("b1", "MODEL_4", 1, CREATED, "o");
        ReflectionTestUtils.setField(s, "periodEnd", end);
        return s;
    }

    private static BusinessSubscription provider(Integer graceDays) {
        BusinessSubscription s = manual(END);
        ReflectionTestUtils.setField(s, "source", "PROVIDER");
        ReflectionTestUtils.setField(s, "graceDays", graceDays);
        return s;
    }

    @Test
    void manualEndsAtTheExactInstant() {
        assertEntitled(at(manual(END), END.minus(NANO)), "ACTIVE");
        assertNotEntitled(at(manual(END), END));
        assertNotEntitled(at(manual(END), END.plus(NANO)));
    }

    @Test
    void ownerWithAnAdminEndEndsAtTheExactInstant() {
        assertEntitled(at(owner(END), END.minus(NANO)), "ACTIVE");
        assertNotEntitled(at(owner(END), END));
    }

    @Test
    void ownerWithoutAnEndStaysEntitled() {
        assertEntitled(at(owner(null), END.plus(Duration.ofDays(3650))), "ACTIVE");
    }

    @Test
    void aReviewedOpenEndedManualRowStaysEntitledPastItsEnd() {
        BusinessSubscription s = manual(END);
        ReflectionTestUtils.setField(s, "openEnded", true);
        assertEntitled(at(s, END.plus(Duration.ofDays(1))), "ACTIVE");
    }

    @Test
    void flagOffLeavesPassedEndsAsStored() {
        evaluator.setPeriodEndEnabled(false);
        assertEntitled(at(manual(END), END.plus(Duration.ofDays(30))), "ACTIVE");
        assertEntitled(at(owner(END), END.plus(Duration.ofDays(30))), "ACTIVE");
        assertEntitled(at(provider(7), END.plus(Duration.ofDays(30))), "ACTIVE");
    }

    @Test
    void trialsEndAtTheirExactInstantWithTheFlagOnOrOff() {
        for (boolean flag : new boolean[]{true, false}) {
            evaluator.setPeriodEndEnabled(flag);
            BusinessSubscription t = BusinessSubscription.trial("b1", 1, CREATED, END, "system");
            assertEntitled(at(t, END.minus(NANO)), "TRIAL");
            assertNotEntitled(at(t, END));
        }
    }

    @Test
    void providerGraceRunsFromThePeriodEndUntilTheExactGraceEnd() {
        assertEntitled(at(provider(7), END.minus(NANO)), "ACTIVE");
        assertEntitled(at(provider(7), END), "PAST_DUE");
        assertEntitled(at(provider(7), END.plus(GRACE).minus(NANO)), "PAST_DUE");
        assertNotEntitled(at(provider(7), END.plus(GRACE)));
    }

    @Test
    void aProviderRowWithoutASnapshottedGraceGetsNoGrace() {
        assertEntitled(at(provider(null), END.minus(NANO)), "ACTIVE");
        assertNotEntitled(at(provider(null), END));
    }

    @Test
    void grandfatheringIsStillDecidedByItsOwnService() {
        when(grandfathering.effectivelyEnded(any(), any())).thenReturn(true);
        assertNotEntitled(at(owner(null), END));
        evaluator.setPeriodEndEnabled(false);
        assertNotEntitled(at(owner(null), END));
    }

    @Test
    void theEntityDelegatesAndKeepsTheFlagOffAnswer() {
        BusinessSubscription m = manual(END);
        assertThat(m.isEffectivelyCurrent(END.plus(Duration.ofDays(1)))).isTrue();
        assertThat(m.effectiveStatus(END.plus(Duration.ofDays(1)))).isEqualTo("ACTIVE");
        BusinessSubscription t = BusinessSubscription.trial("b1", 1, CREATED, END, "system");
        assertThat(t.isEffectivelyCurrent(END)).isFalse();
        assertThat(t.effectiveEnd(END)).isEqualTo(END);
    }
}
