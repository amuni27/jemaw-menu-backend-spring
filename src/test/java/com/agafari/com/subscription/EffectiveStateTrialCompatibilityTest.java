package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R2-01 AC2: for trials, the new evaluator gives exactly what the existing entity methods give today
 * ({@code BusinessSubscription.effectiveStatus} and {@code isEffectivelyCurrent}, lines 109–120, unchanged).
 * 1,000 generated trials (fixed seed, so a failure is reproducible): running and ended, with evaluation times before,
 * exactly at and after the trial end, with the period-end flag on and off.
 */
class EffectiveStateTrialCompatibilityTest {

    private static final long SEED = 20261108L;
    private static final int CASES = 1_000;

    @Test
    void theEvaluatorAgreesWithTheExistingTrialRuleFor1000GeneratedTrials() {
        Random random = new Random(SEED);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        int exactlyAtEnd = 0, ended = 0, expiredResults = 0;
        for (int i = 0; i < CASES; i++) {
            Instant created = base.plusSeconds(random.nextInt(300 * 24 * 3600));
            Instant periodEnd = created.plus(Duration.ofDays(1 + random.nextInt(60))).plusMillis(random.nextInt(86_400_000));
            BusinessSubscription trial = BusinessSubscription.trial("b-" + i, 1, created, periodEnd, "system");
            if (random.nextInt(5) == 0) {
                trial.endTrial(created.plus(Duration.ofDays(1)), created.plus(Duration.ofDays(1)), "admin");
                ended++;
            }
            Instant now;
            switch (i % 4) {
                case 0 -> { now = periodEnd; exactlyAtEnd++; }
                case 1 -> now = periodEnd.minusMillis(1 + random.nextInt(10 * 86_400_000));
                case 2 -> now = periodEnd.plusMillis(1 + random.nextInt(10 * 86_400_000));
                default -> now = periodEnd.plusMillis(random.nextInt(2) == 0 ? 1 : -1);
            }
            boolean flag = random.nextBoolean();
            EffectiveView view = EffectiveState.of(trial, GraceSettings.ofDays(7), flag, false, now);
            assertThat(view.status().name()).as("case %d status", i).isEqualTo(trial.effectiveStatus(now));
            assertThat(view.entitledToPlan()).as("case %d current", i).isEqualTo(trial.isEffectivelyCurrent(now));
            if (view.status() == LifecycleStatus.EXPIRED) expiredResults++;
        }
        // the generated set really covers both outcomes and the exact boundary
        assertThat(exactlyAtEnd).isEqualTo(CASES / 4);
        assertThat(ended).isGreaterThan(100);
        assertThat(expiredResults).isBetween(300, 900);
    }
}
