package com.agafari.com.subscription;

import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.BillingFlags.MarketFlags;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.ScheduledChangeRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.ScheduledChangeService;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** AGF H2 regression: the same invalidation scenarios, run on H2 and on PostgreSQL against a minimal table. */
final class ScheduledChangeInvalidationScenarios {

    private static final String INVALIDATED = "SCHEDULED_CHANGE_INVALIDATED";
    private static final Instant EFFECTIVE = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant BEFORE = EFFECTIVE;
    private static final Instant AFTER = Instant.parse("2026-07-01T12:00:00Z");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    ScheduledChangeInvalidationScenarios(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    Stream<DynamicTest> all() {
        return Stream.of(
                scenario("invalidates the pending change with its audit", this::invalidatesThePendingChange),
                scenario("leaves other rows alone", this::leavesOtherRowsAlone),
                scenario("does nothing with no pending change", this::doesNothingWithNoPendingChange),
                scenario("a row changed after the select is not overwritten or audited", this::aRowChangedAfterTheSelect),
                scenario("a replacement created after the select is not invalidated", this::aReplacementAfterTheSelect),
                scenario("rolls back with the transaction", this::rollsBack),
                scenario("refuses to run without the locks", this::requiresTheLocks));
    }

    private DynamicTest scenario(String name, Runnable body) {
        return dynamicTest(name, () -> {
            audit = mock(AdminAuditService.class);
            locks = mock(MonetizationLocks.class);
            jdbc.execute("drop table if exists scheduled_change");
            jdbc.execute("create table scheduled_change (id varchar(36) primary key, from_subscription_id varchar(36), kind varchar(16), status varchar(16), effective_at timestamp)");
            try {
                body.run();
            } finally {
                jdbc.execute("drop table if exists scheduled_change");
            }
        });
    }

    private void change(String id, String subscription, String status) {
        jdbc.update("insert into scheduled_change (id, from_subscription_id, kind, status, effective_at) values (?, ?, 'DOWNGRADE', ?, ?)", id, subscription, status, Timestamp.from(EFFECTIVE));
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from scheduled_change where id = ?", String.class, id);
    }

    private AdminAuditService audit;
    private MonetizationLocks locks;

    private ScheduledChangeService service(JdbcTemplate template) {
        MarketFlags off = new MarketFlags(false, false, false, false);
        return new ScheduledChangeService(template, mock(ScheduledChangeRepository.class), mock(BusinessSubscriptionRepository.class), mock(MonetizationConfigRepository.class),
                mock(SubscriptionPlanRepository.class), mock(SubscriptionLifecycle.class), locks, audit, new EffectiveStateEvaluator(false), mock(MonetizationClock.class),
                new DataSourceTransactionManager(dataSource), new BillingFlags(false, false, false, off, off));
    }

    /** Runs {@code race} once, straight after the invalidation has read the pending rows. */
    private JdbcTemplate racingAfterSelect(Consumer<JdbcTemplate> race) {
        AtomicBoolean done = new AtomicBoolean();
        JdbcTemplate plain = jdbc;
        return new JdbcTemplate(dataSource) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) throws DataAccessException {
                List<Map<String, Object>> rows = super.queryForList(sql, args);
                if (done.compareAndSet(false, true)) race.accept(plain);
                return rows;
            }
        };
    }

    private void invalidate(JdbcTemplate template) {
        service(template).invalidateForPeriodChange("b1", "s1", BEFORE, AFTER, "admin-1", "a***@example.test");
    }

    private void invalidatesThePendingChange() {
        change("c1", "s1", "PENDING");
        invalidate(jdbc);
        assertThat(status("c1")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select effective_at from scheduled_change where id = 'c1'", Timestamp.class).toInstant()).isEqualTo(EFFECTIVE);
        verify(audit, times(1)).success(INVALIDATED, "admin-1", "a***@example.test", "SCHEDULED_CHANGE", "c1", "DOWNGRADE reason=period_end_changed periodEnd " + BEFORE + " -> " + AFTER);
        verify(locks).assertHeld("b1");
    }

    private void leavesOtherRowsAlone() {
        change("c1", "s1", "APPLIED");
        change("c2", "s2", "PENDING");
        invalidate(jdbc);
        assertThat(status("c1")).isEqualTo("APPLIED");
        assertThat(status("c2")).isEqualTo("PENDING");
        verify(audit, never()).success(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    private void doesNothingWithNoPendingChange() {
        invalidate(jdbc);
        verify(audit, never()).success(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    private void aRowChangedAfterTheSelect() {
        change("c1", "s1", "PENDING");
        invalidate(racingAfterSelect(t -> t.update("update scheduled_change set status = 'APPLIED' where id = 'c1'")));
        assertThat(status("c1")).isEqualTo("APPLIED");
        verify(audit, never()).success(eq(INVALIDATED), any(), anyString(), anyString(), anyString(), anyString());
    }

    private void aReplacementAfterTheSelect() {
        change("c1", "s1", "PENDING");
        invalidate(racingAfterSelect(t -> {
            t.update("update scheduled_change set status = 'SUPERSEDED' where id = 'c1'");
            t.update("insert into scheduled_change (id, from_subscription_id, kind, status, effective_at) values ('c2', 's1', 'DOWNGRADE', 'PENDING', ?)", Timestamp.from(AFTER));
        }));
        assertThat(status("c1")).isEqualTo("SUPERSEDED");
        assertThat(status("c2")).isEqualTo("PENDING");
        verify(audit, never()).success(eq(INVALIDATED), any(), anyString(), anyString(), anyString(), anyString());
    }

    private void rollsBack() {
        change("c1", "s1", "PENDING");
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx -> {
            invalidate(jdbc);
            assertThat(status("c1")).isEqualTo("CANCELLED");
            tx.setRollbackOnly();
        });
        assertThat(status("c1")).isEqualTo("PENDING");
    }

    private void requiresTheLocks() {
        change("c1", "s1", "PENDING");
        doThrow(new IllegalStateException("not held")).when(locks).assertHeld("b1");
        assertThatThrownBy(() -> invalidate(jdbc)).isInstanceOf(IllegalStateException.class);
        assertThat(status("c1")).isEqualTo("PENDING");
    }
}
