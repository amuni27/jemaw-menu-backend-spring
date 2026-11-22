package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R5-03-A (addendum §10; assessment story 1): the daily reconciliation flags a PROVIDER period that ended without a
 * renewal decision. A finding is non-financial: one audited reconciliation event per row, period and kind, never an
 * exception, never a lifecycle change. PostgreSQL; the fake ET provider (SYNTHETIC answers).
 */
class RenewalReconciliationTest extends PaymentOutcomeTestBase {

    @Autowired private ReconciliationService reconciliation;
    @Autowired private EffectiveStateEvaluator evaluator;

    @BeforeEach
    void periodEndOff() {
        evaluator.setPeriodEndEnabled(false); // the finding is about rows no job has decided yet
        // fixture payments are made a month before their period ends: Model 4 enabled since well before that
        int version = jdbc.queryForObject("select max(version) + 1 from monetization_config", Integer.class);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, false, true, ?, now(), ?)", version, Timestamp.from(Instant.now().minus(Duration.ofDays(60))), admin.getId());
    }

    @AfterEach
    void restore() {
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    /** A PROVIDER row (AD-7: 7 days of grace) whose paid period ends {@code untilEnd} from now. */
    private String paidRow(Tenant t, Duration untilEnd) {
        Instant periodEnd = micros(Instant.now().plus(untilEnd));
        Instant tPay = periodEnd.atZone(ZoneOffset.UTC).minusMonths(1).toInstant();
        assertThat(payAndProcess(checkout(t, CheckoutKind.NEW, null), tPay)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        String sub = current(t);
        assertThat(periodEnd(sub)).isEqualTo(periodEnd);
        return sub;
    }

    private Instant periodEnd(String sub) {
        return jdbc.queryForObject("select period_end from business_subscription where id = ?", Timestamp.class, sub).toInstant();
    }

    private void at(Instant t) {
        clock.setOffset(Duration.between(Instant.now(), t));
    }

    private ReconciliationService.DailyResult daily() {
        return reconciliation.daily(clock.now());
    }

    private List<String> findings(String sub) {
        return jdbc.queryForList("select detail from admin_audit_event where action = ? and target_id = ? order by occurred_at", String.class,
                ReconciliationService.RENEWAL_UNDECIDED, sub);
    }

    private void locked(Tenant t, Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            work.run();
        });
    }

    @Test
    void anActiveRowADayPastItsPeriodEndIsFlaggedOnceAndNothingElseChanges() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        Instant pe = periodEnd(sub);
        at(pe.plus(Duration.ofHours(25)));
        int exceptions = exceptions(owner);

        assertThat(daily().renewalFindings()).isEqualTo(1);
        assertThat(findings(sub)).singleElement().asString().contains("kind=ACTIVE_AFTER_PERIOD_END").contains("periodEnd=" + pe);
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, sub)).as("never a lifecycle change").isEqualTo("ACTIVE");
        assertThat(exceptions(owner)).as("non-financial: never an exception").isEqualTo(exceptions);

        assertThat(daily().renewalFindings()).as("idempotent per row and period").isZero();
        assertThat(findings(sub)).hasSize(1);
    }

    @Test
    void notBeforeTheDecisionWindowHasPassed() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        at(periodEnd(sub).plus(Duration.ofHours(23)));
        assertThat(daily().renewalFindings()).isZero();
        assertThat(findings(sub)).isEmpty();
    }

    @Test
    void aPastDueRowStillCurrentAfterItsGraceEndIsFlagged() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        Instant pe = periodEnd(sub);
        at(pe.plus(Duration.ofHours(2)));
        locked(owner, () -> lifecycle.markPastDue(sub, pe, clock.now()));
        at(pe.plus(Duration.ofDays(3)));
        assertThat(daily().renewalFindings()).as("PAST_DUE inside grace is a decision in progress").isZero();

        at(pe.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
        assertThat(daily().renewalFindings()).isEqualTo(1);
        assertThat(findings(sub)).singleElement().asString().contains("kind=PAST_DUE_AFTER_GRACE").contains("graceEnd=" + pe.plus(Duration.ofDays(7)));
    }

    @Test
    void aRenewedRowIsNotFlaggedAndItsNextPeriodIsJudgedOnItsOwn() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        Instant pe = periodEnd(sub);
        at(pe.plus(Duration.ofHours(25)));
        assertThat(daily().renewalFindings()).isEqualTo(1);

        assertThat(payAndProcess(checkout(owner, CheckoutKind.RENEWAL, sub), micros(pe.plusSeconds(60)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        Instant next = periodEnd(sub);
        assertThat(next).isEqualTo(oneMonth(pe));
        assertThat(daily().renewalFindings()).as("renewed: nothing undecided").isZero();

        at(next.plus(Duration.ofHours(25)));
        assertThat(daily().renewalFindings()).as("the next period is its own finding").isEqualTo(1);
        assertThat(findings(sub)).hasSize(2).last().asString().contains("periodEnd=" + next);
    }

    @Test
    void anEndedRowIsNeverFlagged() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        at(periodEnd(sub).plus(Duration.ofDays(8)));
        evaluator.setPeriodEndEnabled(true);
        locked(owner, () -> lifecycle.materialize(owner.businessId()));
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, sub)).isEqualTo("EXPIRED");
        assertThat(daily().renewalFindings()).isZero();
        assertThat(findings(sub)).isEmpty();
    }

    @Test
    void trialAndOwnerRowsAreNeverFlagged() {
        startTrial(owner, clock.now().plus(1, ChronoUnit.DAYS));
        Tenant free = data.tenant("renewal-recon-free");
        startOwnerFree(free);
        String trial = current(owner), own = current(free);
        at(Instant.now().plus(Duration.ofDays(40)));
        daily();
        assertThat(findings(trial)).isEmpty();
        assertThat(findings(own)).isEmpty();
    }

    @Test
    void onlyForMarketsWhoseBillingIsActive() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        at(periodEnd(sub).plus(Duration.ofHours(25)));
        outcomes.setBillingEnabled(Market.ET, false);
        assertThat(daily().renewalFindings()).isZero();
        assertThat(findings(sub)).isEmpty();
    }

    @Test
    void eachRestaurantsRowIsFlaggedOnItsOwn() {
        String a = paidRow(owner, Duration.ofMinutes(5));
        Tenant other = data.tenant("renewal-recon-b");
        String b = paidRow(other, Duration.ofHours(30));
        at(periodEnd(a).plus(Duration.ofHours(25)));
        assertThat(daily().renewalFindings()).isEqualTo(1);
        assertThat(findings(a)).hasSize(1);
        assertThat(findings(b)).as("B's period hasn't been over for a day").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = ? and target_id in (?, ?)", Integer.class,
                ReconciliationService.RENEWAL_UNDECIDED, a, b)).isEqualTo(1);
    }
}
