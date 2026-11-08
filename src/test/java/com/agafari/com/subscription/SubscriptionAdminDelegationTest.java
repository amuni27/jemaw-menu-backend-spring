package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF SUB-R2-03: Super Admin subscription writes on real PostgreSQL. The behaviour tests (positive edits, grandfathered rule,
 * transition dates, exact audit rows) also passed on the code before delegation, so they prove nothing changed. The PROVIDER and
 * lock tests are new behaviour.
 */
class SubscriptionAdminDelegationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private MonetizationLocks locks;
    @Autowired private BusinessSubscriptionRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    private User admin;
    private String bearer;
    private Tenant owner;
    private String actorLabel;

    @BeforeEach
    void setUp() {
        admin = data.admin("delegation-admin");
        bearer = data.adminBearer(admin);
        owner = data.tenant("delegation-owner");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId()); // MODEL_3 is not enabled: a MODEL_3 row is grandfathered
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private String row(String source, String status, String plan, Instant periodEnd, Instant transition) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, transition_date, source, created_at, "
                        + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, ?, 1, ?, ?, ?, now() - interval '1 day', ?, now() - interval '1 day', ?, 0)",
                id, owner.businessId(), plan, status, periodEnd == null ? null : Timestamp.from(periodEnd), transition == null ? null : Timestamp.from(transition),
                source, admin.getId(), admin.getId());
        return id;
    }

    private ResultActions change(String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions periodEnd(Instant end) throws Exception {
        return change("{\"periodEnd\":\"" + end + "\",\"rowVersion\":0}");
    }

    private Map<String, Object> subscription(String id) {
        return jdbc.queryForMap("select status, period_end, transition_date, ended_at, updated_by_user_id, row_version from business_subscription where id = ?", id);
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private void assertSingleAudit(String action, String outcome, String targetId, String detail) {
        List<Map<String, Object>> rows = jdbc.queryForList("select action, outcome, actor_user_id, target_type, target_id, detail from admin_audit_event "
                + "where action not like 'ADMIN_%' order by occurred_at");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("action", action).containsEntry("outcome", outcome).containsEntry("actor_user_id", admin.getId())
                .containsEntry("target_id", targetId).containsEntry("detail", detail);
        if (targetId != null) assertThat(rows.get(0)).containsEntry("target_type", "BUSINESS_SUBSCRIPTION");
    }

    private void assertEdited(String id, Instant end, int version) {
        Map<String, Object> row = subscription(id);
        assertThat(instant(row.get("period_end"))).isEqualTo(end);
        assertThat(row).containsEntry("updated_by_user_id", admin.getId()).containsEntry("row_version", version);
    }

    private ZoneId zone() {
        return ZoneId.of(jdbc.queryForObject("select time_zone from business where id = ?", String.class, owner.businessId()));
    }

    // ---- positive period edits, with the exact audit row

    @Test
    void aManualPeriodCanBeShortened() throws Exception {
        Instant before = days(60), after = days(30);
        String id = row("MANUAL", "ACTIVE", "MODEL_4", before, null);
        periodEnd(after).andExpect(status().isOk()).andExpect(jsonPath("$.rowVersion").value(1));
        assertEdited(id, after, 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_4 ACTIVE -> ACTIVE periodEnd " + before + " -> " + after + " source=MANUAL");
    }

    @Test
    void aManualPeriodCanBeExtended() throws Exception {
        Instant before = days(30), after = days(90);
        String id = row("MANUAL", "ACTIVE", "MODEL_4", before, null);
        periodEnd(after).andExpect(status().isOk());
        assertEdited(id, after, 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_4 ACTIVE -> ACTIVE periodEnd " + before + " -> " + after + " source=MANUAL");
    }

    @Test
    void aTrialPeriodCanBeShortened() throws Exception {
        Instant before = days(20), after = days(5);
        String id = row("TRIAL", "TRIAL", "MODEL_1", before, null);
        periodEnd(after).andExpect(status().isOk());
        assertEdited(id, after, 1);
        assertSingleAudit("TRIAL_EXTENDED", "SUCCESS", id, "MODEL_1 TRIAL -> TRIAL periodEnd " + before + " -> " + after + " source=TRIAL");
    }

    @Test
    void aTrialPeriodCanBeExtended() throws Exception {
        Instant before = days(5), after = days(40);
        String id = row("TRIAL", "TRIAL", "MODEL_1", before, null);
        periodEnd(after).andExpect(status().isOk());
        assertEdited(id, after, 1);
        assertSingleAudit("TRIAL_EXTENDED", "SUCCESS", id, "MODEL_1 TRIAL -> TRIAL periodEnd " + before + " -> " + after + " source=TRIAL");
    }

    @Test
    void aTrialCanBeMovedToACalendarDateInTheRestaurantsTimeZone() throws Exception {
        Instant before = days(5);
        String id = row("TRIAL", "TRIAL", "MODEL_1", before, null);
        LocalDate date = LocalDate.now(zone()).plusDays(50);
        Instant expected = date.plusDays(1).atStartOfDay(zone()).toInstant();
        change("{\"periodEndDate\":\"" + date + "\",\"rowVersion\":0}").andExpect(status().isOk());
        assertEdited(id, expected, 1);
        assertSingleAudit("TRIAL_EXTENDED", "SUCCESS", id, "MODEL_1 TRIAL -> TRIAL periodEnd " + before + " -> " + expected + " source=TRIAL");
    }

    @Test
    void anOwnerEndDateCanBeSetOnAnOpenEndedRow() throws Exception {
        Instant after = days(45);
        String id = row("OWNER", "ACTIVE", "MODEL_2", null, null);
        periodEnd(after).andExpect(status().isOk());
        assertEdited(id, after, 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_2 ACTIVE -> ACTIVE periodEnd null -> " + after + " source=OWNER");
    }

    @Test
    void anOwnerEndDateCanBeModified() throws Exception {
        Instant before = days(45), after = days(15);
        String id = row("OWNER", "ACTIVE", "MODEL_2", before, null);
        periodEnd(after).andExpect(status().isOk());
        assertEdited(id, after, 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_2 ACTIVE -> ACTIVE periodEnd " + before + " -> " + after + " source=OWNER");
    }

    // ---- validation, optimistic locking and ending

    @Test
    void aPastDateIsStillRefusedWithTheExistingMessage() throws Exception {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        periodEnd(days(-1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].message").value("Choose a date in the future"));
        assertThat(subscription(id)).containsEntry("row_version", 0);
    }

    @Test
    void aStaleRowVersionIsStillAConflictAndChangesNothing() throws Exception {
        Instant before = days(30);
        String id = row("MANUAL", "ACTIVE", "MODEL_4", before, null);
        change("{\"periodEnd\":\"" + days(60) + "\",\"rowVersion\":7}").andExpect(status().isConflict());
        assertThat(instant(subscription(id).get("period_end"))).isEqualTo(before);
        assertSingleAudit("SUBSCRIPTION_CHANGE_REJECTED", "FAILURE", null, "version_conflict");
    }

    @Test
    void endingKeepsItsAuditRowAndStamps() throws Exception {
        Instant before = days(30);
        String id = row("MANUAL", "ACTIVE", "MODEL_4", before, null);
        change("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk());
        Map<String, Object> row = subscription(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("row_version", 1);
        assertThat(row.get("ended_at")).isNotNull();
        assertThat(instant(jdbc.queryForObject("select updated_at from business_subscription where id = ?", Timestamp.class, id))).isEqualTo(instant(row.get("ended_at")));
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_4 ACTIVE -> EXPIRED periodEnd " + before + " -> " + before + " source=MANUAL");
    }

    @Test
    void endingATrialKeepsItsAuditAction() throws Exception {
        Instant before = days(30);
        String id = row("TRIAL", "TRIAL", "MODEL_1", before, null);
        change("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk());
        assertSingleAudit("TRIAL_ENDED", "SUCCESS", id, "MODEL_1 TRIAL -> EXPIRED periodEnd " + before + " -> " + before + " source=TRIAL");
    }

    @Test
    void aPeriodChangeAndAnEndInOneRequestBumpTheVersionOnce() throws Exception {
        Instant before = days(30), after = days(40);
        String id = row("MANUAL", "ACTIVE", "MODEL_4", before, null);
        change("{\"periodEnd\":\"" + after + "\",\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk());
        assertThat(subscription(id)).containsEntry("status", "EXPIRED").containsEntry("row_version", 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_4 ACTIVE -> EXPIRED periodEnd " + before + " -> " + after + " source=MANUAL");
    }

    // ---- grandfathered rows and transition dates

    @Test
    void aGrandfatheredRowCanBeExtended() throws Exception {
        Instant before = days(30), after = days(60);
        String id = row("MANUAL", "ACTIVE", "MODEL_3", before, null);
        periodEnd(after).andExpect(status().isOk());
        assertEdited(id, after, 1);
    }

    @Test
    void aGrandfatheredRowCantBeShortened() throws Exception {
        Instant before = days(60);
        String id = row("MANUAL", "ACTIVE", "MODEL_3", before, null);
        periodEnd(days(30)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The end date can only be extended while the subscription is grandfathered"));
        assertThat(instant(subscription(id).get("period_end"))).isEqualTo(before);
        assertSingleAudit("SUBSCRIPTION_CHANGE_REJECTED", "FAILURE", null, "invalid");
    }

    private ResultActions transition(String date) throws Exception {
        return mockMvc.perform(put("/api/admin/businesses/{id}/subscription/transition-date", owner.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"transitionDate\":\"" + date + "\",\"rowVersion\":0}"));
    }

    @Test
    void aTransitionDateIsSetWithItsExactAuditRow() throws Exception {
        String id = row("MANUAL", "ACTIVE", "MODEL_3", null, null);
        LocalDate date = LocalDate.now(zone()).plusDays(30);
        Instant expected = date.plusDays(1).atStartOfDay(zone()).toInstant();
        transition(date.toString()).andExpect(status().isOk());
        Map<String, Object> row = subscription(id);
        assertThat(instant(row.get("transition_date"))).isEqualTo(expected);
        assertThat(row).containsEntry("row_version", 1).containsEntry("updated_by_user_id", admin.getId());
        assertSingleAudit("SUBSCRIPTION_TRANSITION_SET", "SUCCESS", id, "config=1;transition=" + expected);
    }

    @Test
    void aTransitionDateIsClearedWithItsExactAuditRow() throws Exception {
        Instant date = days(30);
        String id = row("MANUAL", "ACTIVE", "MODEL_3", null, date);
        mockMvc.perform(post("/api/admin/businesses/{id}/subscription/transition-date/clear", owner.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVersion\":0}")).andExpect(status().isOk());
        assertThat(subscription(id).get("transition_date")).isNull();
        assertSingleAudit("SUBSCRIPTION_TRANSITION_CLEARED", "SUCCESS", id, "config=1;transition=" + date);
    }

    @Test
    void aPeriodEndOnAnOpenEndedRowClearsTheTransitionDateAndSaysSo() throws Exception {
        Instant date = days(30), after = days(45);
        String id = row("MANUAL", "ACTIVE", "MODEL_3", null, date);
        periodEnd(after).andExpect(status().isOk());
        Map<String, Object> row = subscription(id);
        assertThat(row.get("transition_date")).isNull();
        assertThat(row).containsEntry("row_version", 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_3 ACTIVE -> ACTIVE periodEnd null -> " + after + " source=MANUAL transitionDate " + date + " -> cleared");
    }

    // ---- PROVIDER periods (R-4)

    private void assertProviderRefused(ResultActions result, String id, Instant before) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROVIDER_PERIOD_MANAGED_BY_BILLING"));
        Map<String, Object> row = subscription(id);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(instant(row.get("period_end"))).isEqualTo(before);
        assertSingleAudit("SUBSCRIPTION_CHANGE_REJECTED", "FAILURE", null, "provider_period");
    }

    @Test
    void aProviderPeriodCantBeExtended() throws Exception {
        Instant before = days(30);
        String id = row("PROVIDER", "ACTIVE", "MODEL_4", before, null);
        assertProviderRefused(periodEnd(days(60)), id, before);
    }

    @Test
    void aProviderPeriodCantBeShortened() throws Exception {
        Instant before = days(60);
        String id = row("PROVIDER", "ACTIVE", "MODEL_4", before, null);
        assertProviderRefused(periodEnd(days(30)), id, before);
    }

    @Test
    void aGrandfatheredProviderPeriodIsRefusedAsAProviderPeriod() throws Exception {
        Instant before = days(60);
        String id = row("PROVIDER", "ACTIVE", "MODEL_3", before, null);
        assertProviderRefused(periodEnd(days(30)), id, before);
    }

    @Test
    void aProviderPeriodCantBeMovedToACalendarDate() throws Exception {
        Instant before = days(30);
        String id = row("PROVIDER", "ACTIVE", "MODEL_4", before, null);
        assertProviderRefused(change("{\"periodEndDate\":\"" + LocalDate.now(zone()).plusDays(50) + "\",\"rowVersion\":0}"), id, before);
    }

    @Test
    void aPeriodChangeAndAnEndTogetherAreRefusedForAProviderRow() throws Exception {
        Instant before = days(30);
        String id = row("PROVIDER", "ACTIVE", "MODEL_4", before, null);
        assertProviderRefused(change("{\"periodEnd\":\"" + days(60) + "\",\"status\":\"EXPIRED\",\"rowVersion\":0}"), id, before);
    }

    @Test
    void aProviderRowCanStillBeEndedByTheSuperAdmin() throws Exception {
        Instant before = days(30);
        String id = row("PROVIDER", "ACTIVE", "MODEL_4", before, null);
        change("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk());
        assertThat(subscription(id)).containsEntry("status", "EXPIRED");
        assertThat(instant(subscription(id).get("period_end"))).isEqualTo(before);
    }

    @Test
    void aStaleRowVersionOnAProviderRowIsAVersionConflictFirst() throws Exception {
        row("PROVIDER", "ACTIVE", "MODEL_4", days(30), null);
        change("{\"periodEnd\":\"" + days(60) + "\",\"rowVersion\":9}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_VERSION_CONFLICT"));
    }

    // ---- a stored GRANDFATHERED row: the accepted transition table has no period change for it

    private void assertTransitionRefused(ResultActions result, String id, Instant before) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_TRANSITION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("This subscription can't be changed this way."));
        Map<String, Object> row = subscription(id);
        assertThat(row).containsEntry("status", "GRANDFATHERED").containsEntry("row_version", 0).containsEntry("updated_by_user_id", admin.getId());
        assertThat(instant(row.get("period_end"))).isEqualTo(before);
        assertThat(row.get("ended_at")).isNull();
        assertSingleAudit("SUBSCRIPTION_CHANGE_REJECTED", "FAILURE", null, "transition_not_allowed");
    }

    @Test
    void aStoredGrandfatheredPeriodEditIsAConflictNotAServerError() throws Exception {
        Instant before = days(30);
        String id = row("MANUAL", "GRANDFATHERED", "MODEL_4", before, null);
        assertTransitionRefused(periodEnd(days(60)), id, before);
    }

    @Test
    void aStoredGrandfatheredPeriodEditWithAnEndIsRefusedWhole() throws Exception {
        Instant before = days(30);
        String id = row("MANUAL", "GRANDFATHERED", "MODEL_4", before, null);
        assertTransitionRefused(change("{\"periodEnd\":\"" + days(60) + "\",\"status\":\"EXPIRED\",\"rowVersion\":0}"), id, before);
    }

    @Test
    void aStoredGrandfatheredRowCanStillBeEnded() throws Exception {
        Instant before = days(30);
        String id = row("MANUAL", "GRANDFATHERED", "MODEL_4", before, null);
        change("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk());
        assertThat(subscription(id)).containsEntry("status", "EXPIRED").containsEntry("row_version", 1);
        assertSingleAudit("SUBSCRIPTION_UPDATED", "SUCCESS", id, "MODEL_4 GRANDFATHERED -> EXPIRED periodEnd " + before + " -> " + before + " source=MANUAL");
    }

    // ---- lock ownership per transaction and restaurant

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    private void edit(String id) {
        BusinessSubscription s = repository.findById(id).orElseThrow();
        lifecycle.applyAdminChange(s, new AdminPeriodChange(days(99), false, false, Instant.now()), new Actor(admin.getId()));
    }

    private void assertUntouched(String id) {
        assertThat(subscription(id)).containsEntry("row_version", 0);
    }

    @Test
    void aChangeIsRefusedOutsideATransaction() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        assertThatThrownBy(() -> edit(id)).isInstanceOf(IllegalStateException.class);
        assertUntouched(id);
    }

    @Test
    void aChangeIsRefusedWithoutTheLocks() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> edit(id))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> { locks.configShared(); edit(id); })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> { locks.restaurant(owner.businessId()); edit(id); })).isInstanceOf(IllegalStateException.class);
        assertUntouched(id);
    }

    @Test
    void anotherRestaurantsLockDoesNotCount() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        Tenant other = data.tenant("delegation-other");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> { locks.configShared(); locks.restaurant(other.businessId()); edit(id); }))
                .isInstanceOf(IllegalStateException.class);
        assertUntouched(id);
    }

    @Test
    void locksFromAFailedTransactionDoNotCarryOver() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            throw new IllegalArgumentException("fails after locking");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> edit(id))).isInstanceOf(IllegalStateException.class);
        assertUntouched(id);
    }

    @Test
    void aFailureAfterTheChangeRollsItBack() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            edit(id);
            throw new IllegalArgumentException("fails after the change");
        })).isInstanceOf(IllegalArgumentException.class);
        assertUntouched(id);
    }

    @Test
    void aNestedParticipatingTransactionKeepsTheLocks() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        tx().executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            tx().executeWithoutResult(inner -> edit(id));
        });
        assertThat(subscription(id)).containsEntry("row_version", 1);
    }

    @Test
    void aSuspendedTransactionsLocksDoNotCountInTheInnerOneAndComeBackAfterIt() {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        tx().executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            assertThatThrownBy(() -> requiresNew().executeWithoutResult(inner -> locks.assertHeld(owner.businessId())))
                    .isInstanceOf(IllegalStateException.class);
            edit(id);
        });
        assertThat(subscription(id)).containsEntry("row_version", 1);
    }

    // ---- serialization (not relying on any unique index: a period edit inserts nothing)

    private <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoConcurrentEditsWithOneRowVersionHaveExactlyOneWinner() throws Exception {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        Instant a = days(40), b = days(50);
        List<Integer> statuses = together(List.of(
                () -> periodEnd(a).andReturn().getResponse().getStatus(),
                () -> periodEnd(b).andReturn().getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(subscription(id)).containsEntry("row_version", 1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'SUBSCRIPTION_UPDATED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'SUBSCRIPTION_CHANGE_REJECTED' and detail = 'version_conflict'", Integer.class)).isEqualTo(1);
    }

    @Test
    void anEditWaitsForTheRestaurantLockHeldByAnotherTransaction() throws Exception {
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30), null);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(s -> {
                locks.restaurant(owner.businessId());
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> edit = pool.submit(() -> periodEnd(days(40)).andReturn().getResponse().getStatus());
            waitUntilALockQueryIsBlocked();
            assertThat(edit.isDone()).isFalse();
            assertUntouched(id);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(edit.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(subscription(id)).containsEntry("row_version", 1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void waitUntilALockQueryIsBlocked() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                    + "and query like 'select id from business where id = % for no key update%'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("The edit never waited for the restaurant lock");
    }
}
