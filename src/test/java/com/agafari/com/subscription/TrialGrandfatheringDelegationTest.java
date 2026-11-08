package com.agafari.com.subscription;

import com.agafari.com.dto.request.TrialConvertRequest;
import com.agafari.com.dto.request.TrialStartRequest;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.LogMasking;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.TrialExpirer;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * AGF SUB-R2-04: trial and grandfathering writes on real PostgreSQL. The exact row and audit tests also passed on the code
 * before delegation, so they prove nothing changed. The lock tests cover the new lifecycle commands.
 */
class TrialGrandfatheringDelegationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "delegation-admin@example.test";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private TrialExpirer expirer;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private MonetizationLocks locks;
    @Autowired private BusinessSubscriptionRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-delegation-admin");
        owner = data.tenant("trial-delegation-owner");
        trials.setEnabled(true);
    }

    @AfterEach
    void reset() {
        trials.setEnabled(false);
        grandfathering.setEnabled(false);
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private String row(Tenant tenant, String source, String status, String plan, Instant periodEnd, Instant created) {
        if (jdbc.queryForObject("select count(*) from monetization_config where version = 1", Integer.class) == 0) {
            config(jdbc, 1, "SETTING_1", false, false, false, admin.getId()); // subscriptions reference a configuration version
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                        + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 0)",
                id, tenant.businessId(), plan, status, periodEnd == null ? null : Timestamp.from(periodEnd), source, Timestamp.from(created), admin.getId(),
                Timestamp.from(created), admin.getId());
        return id;
    }

    private String trial(Instant end) {
        return row(owner, "TRIAL", "TRIAL", "MODEL_1", end, days(-40));
    }

    private Map<String, Object> subscription(String id) {
        return jdbc.queryForMap("select plan_code, status, source, config_version, period_end, ended_at, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version "
                + "from business_subscription where id = ?", id);
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private List<Map<String, Object>> audits() {
        return jdbc.queryForList("select action, outcome, actor_user_id, actor_label, target_type, target_id, detail from admin_audit_event "
                + "where action not like 'ADMIN_%' order by occurred_at");
    }

    private static void assertAudit(Map<String, Object> row, String action, String actorId, String label, String target, String detail) {
        assertThat(row).containsEntry("action", action).containsEntry("outcome", "SUCCESS").containsEntry("actor_user_id", actorId)
                .containsEntry("actor_label", label).containsEntry("target_type", "BUSINESS_SUBSCRIPTION").containsEntry("target_id", target);
        assertThat(row.get("detail")).isEqualTo(detail);
    }

    private String currentId() {
        return jdbc.queryForObject("select id from business_subscription where business_id = ? and status <> 'EXPIRED'", String.class, owner.businessId());
    }

    // ---- exact rows and audit

    @Test
    void anAdminStartedTrialKeepsItsRowAndAudit() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.start(owner.businessId(), new TrialStartRequest(1), admin.getId(), ADMIN_EMAIL);
        String id = currentId();
        Map<String, Object> row = subscription(id);
        assertThat(row).containsEntry("plan_code", "MODEL_1").containsEntry("status", "TRIAL").containsEntry("source", "TRIAL")
                .containsEntry("config_version", 1).containsEntry("created_by_user_id", admin.getId()).containsEntry("row_version", 0);
        assertThat(Duration.between(instant(row.get("created_at")), instant(row.get("period_end")))).isEqualTo(Duration.ofDays(30));
        List<Map<String, Object>> rows = audits();
        assertThat(rows).hasSize(1);
        assertAudit(rows.get(0), "TRIAL_STARTED", admin.getId(), LogMasking.maskEmail(ADMIN_EMAIL), id, "MODEL_1 TRIAL config=1 periodEnd=" + instant(row.get("period_end")));
    }

    @Test
    void aConversionKeepsItsRowsAndAuditOrder() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String trialId = trial(days(20));
        Instant manualEnd = days(100);
        trials.convert(owner.businessId(), new TrialConvertRequest(1, 0, manualEnd), admin.getId(), ADMIN_EMAIL);
        Map<String, Object> ended = subscription(trialId);
        assertThat(ended).containsEntry("status", "EXPIRED").containsEntry("source", "TRIAL").containsEntry("row_version", 1).containsEntry("updated_by_user_id", admin.getId());
        assertThat(instant(ended.get("ended_at"))).isEqualTo(instant(ended.get("updated_at")));
        String manualId = currentId();
        Map<String, Object> manual = subscription(manualId);
        assertThat(manual).containsEntry("plan_code", "MODEL_1").containsEntry("status", "ACTIVE").containsEntry("source", "MANUAL")
                .containsEntry("created_by_user_id", admin.getId()).containsEntry("row_version", 0);
        assertThat(instant(manual.get("period_end"))).isEqualTo(manualEnd);
        assertThat(instant(manual.get("created_at"))).isEqualTo(instant(ended.get("updated_at")));
        List<Map<String, Object>> rows = audits();
        assertThat(rows).hasSize(2);
        String label = LogMasking.maskEmail(ADMIN_EMAIL);
        assertAudit(rows.get(0), "SUBSCRIPTION_CREATED", admin.getId(), label, manualId, "MODEL_1 ACTIVE source=MANUAL config=1 periodEnd=" + manualEnd);
        assertAudit(rows.get(1), "TRIAL_CONVERTED", admin.getId(), label, trialId, "trial=" + trialId + " -> subscription=" + manualId + " source=MANUAL config=1");
    }

    @Test
    void anEffectivelyEndedTrialKeepsItsRealEndWhenConverted() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        Instant end = days(-2);
        String trialId = trial(end);
        trials.convert(owner.businessId(), new TrialConvertRequest(1, 0, null), admin.getId(), ADMIN_EMAIL);
        assertThat(instant(subscription(trialId).get("ended_at"))).isEqualTo(end);
    }

    @Test
    void aStaleConversionIsRefusedAndChangesNothing() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String trialId = trial(days(20));
        assertThatThrownBy(() -> trials.convert(owner.businessId(), new TrialConvertRequest(1, 5, null), admin.getId(), ADMIN_EMAIL)).isNotNull();
        assertThat(subscription(trialId)).containsEntry("status", "TRIAL").containsEntry("row_version", 0);
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, owner.businessId())).isEqualTo(1);
    }

    private void assertExpiredBySystem(String id, Instant end, String via) {
        Map<String, Object> row = subscription(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("updated_by_user_id", "system").containsEntry("row_version", 1);
        assertThat(instant(row.get("ended_at"))).isEqualTo(end);
        List<Map<String, Object>> rows = audits();
        assertThat(rows).hasSize(1);
        assertAudit(rows.get(0), "TRIAL_EXPIRED", null, "system", id, "MODEL_1 TRIAL -> EXPIRED " + via);
    }

    @Test
    void trialExpiryOnTouchKeepsItsRowAndAudit() {
        Instant end = days(-1);
        String id = trial(end);
        assertThat(expirer.expireDueOnTouch(owner.businessId())).isTrue();
        assertExpiredBySystem(id, end, "on_touch");
        assertThat(expirer.expireDueOnTouch(owner.businessId())).isFalse();
    }

    @Test
    void trialExpiryByTheJobKeepsItsRowAndAuditAndIsIdempotent() {
        Instant end = days(-1);
        String id = trial(end);
        assertThat(trials.runExpiry()).isEqualTo(1);
        assertExpiredBySystem(id, end, "job");
        assertThat(trials.runExpiry()).isZero();
        assertThat(audits()).hasSize(1);
    }

    @Test
    void aRunningTrialIsNotExpired() {
        String id = trial(days(5));
        assertThat(expirer.expireDueOnTouch(owner.businessId())).isFalse();
        assertThat(trials.runExpiry()).isZero();
        assertThat(subscription(id)).containsEntry("status", "TRIAL").containsEntry("row_version", 0);
        assertThat(audits()).isEmpty();
    }

    private void assertGrandfatherEnded(String id, Instant end, String via) {
        Map<String, Object> row = subscription(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("updated_by_user_id", "system").containsEntry("row_version", 1);
        assertThat(instant(row.get("ended_at"))).isEqualTo(end);
        List<Map<String, Object>> rows = audits();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("action", "GRANDFATHERING_ENDED").containsEntry("outcome", "SUCCESS").containsEntry("actor_label", "system")
                .containsEntry("target_type", "BUSINESS_SUBSCRIPTION").containsEntry("target_id", id);
        assertThat(rows.get(0).get("actor_user_id")).isNull();
        assertThat((String) rows.get(0).get("detail")).matches(Pattern.quote("MODEL_3 ACTIVE -> EXPIRED source=period_end config=1 current=1 endedAt=" + end + " processedAt=")
                + "\\S+" + Pattern.quote(" processed_late via=" + via));
    }

    @Test
    void aGrandfatheredEndOnTouchKeepsItsRowAndAudit() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        grandfathering.setEnabled(true);
        Instant end = days(-3);
        String id = row(owner, "MANUAL", "ACTIVE", "MODEL_3", end, days(-40));
        assertThat(grandfathering.expireDueOnTouch(owner.businessId())).isTrue();
        assertGrandfatherEnded(id, end, "on_touch");
    }

    @Test
    void aGrandfatheredEndByTheJobKeepsItsRowAndAuditAndIsIdempotent() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        grandfathering.setEnabled(true);
        Instant end = days(-3);
        String id = row(owner, "MANUAL", "ACTIVE", "MODEL_3", end, days(-40));
        assertThat(grandfathering.runEnd()).isEqualTo(1);
        assertGrandfatherEnded(id, end, "job");
        assertThat(grandfathering.runEnd()).isZero();
        assertThat(audits()).hasSize(1);
    }

    @Test
    void withTransitionsOffNothingEnds() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String id = row(owner, "MANUAL", "ACTIVE", "MODEL_3", days(-3), days(-40));
        assertThat(grandfathering.expireDueOnTouch(owner.businessId())).isFalse();
        assertThat(grandfathering.runEnd()).isZero();
        assertThat(subscription(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
    }

    // ---- lock ownership

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private BusinessSubscription load(String id) {
        return repository.findById(id).orElseThrow();
    }

    private ManualRecord successor() {
        return new ManualRecord("MODEL_1", 1, null, Instant.now());
    }

    private Actor actor() {
        return new Actor(admin.getId());
    }

    @Test
    void trialCommandsAreRefusedWithoutTheirLocks() {
        String id = trial(days(-1));
        Tenant other = data.tenant("trial-delegation-other");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> lifecycle.expireTrialIfDue(owner.businessId(), id, Instant.now()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> { locks.restaurant(other.businessId()); lifecycle.expireTrialIfDue(owner.businessId(), id, Instant.now()); }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> lifecycle.replace(load(id), Instant.now(), successor(), actor()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> { locks.restaurant(owner.businessId()); lifecycle.replace(load(id), Instant.now(), successor(), actor()); }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> lifecycle.startTrial(other.businessId(), 1, Instant.now(), days(30), actor()))).isInstanceOf(IllegalStateException.class);
        assertThat(subscription(id)).containsEntry("status", "TRIAL").containsEntry("row_version", 0);
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isEqualTo(1);
    }

    @Test
    void trialExpiryOnlyNeedsTheRestaurantLock() {
        String id = trial(days(-1));
        Boolean expired = tx().execute(s -> { locks.restaurant(owner.businessId()); return lifecycle.expireTrialIfDue(owner.businessId(), id, Instant.now()); });
        assertThat(expired).isTrue();
    }

    @Test
    void aGrandfatheredEndIsRefusedWithoutTheConfigurationLock() {
        String id = row(owner, "MANUAL", "ACTIVE", "MODEL_3", days(-3), days(-40));
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> { locks.restaurant(owner.businessId()); lifecycle.endGrandfathered(load(id), days(-3), Instant.now()); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(subscription(id)).containsEntry("status", "ACTIVE");
    }

    @Test
    void aFailureAfterAReplacementRollsBothRowsBack() {
        String id = trial(days(20));
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            lifecycle.replace(load(id), Instant.now(), successor(), actor());
            throw new IllegalArgumentException("fails after the replacement");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription(id)).containsEntry("status", "TRIAL").containsEntry("row_version", 0);
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, owner.businessId())).isEqualTo(1);
    }

    // ---- conversion vs admin extension (SUB-R2-04 test list)

    @Test
    void aConversionAndAnAdminExtensionNeverLeaveTwoCurrentRowsOrALostChange() throws Exception {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String bearer = data.adminBearer(admin);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                Tenant t = data.tenant("trial-race-" + i);
                String trialId = row(t, "TRIAL", "TRIAL", "MODEL_1", days(10), days(-5));
                Instant extended = days(40);
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> extend = pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"periodEnd\":\"" + extended + "\",\"rowVersion\":0}")).andReturn().getResponse().getStatus();
                });
                Future<String> convert = pool.submit(() -> {
                    start.await();
                    try {
                        trials.convert(t.businessId(), new TrialConvertRequest(1, 0, null), admin.getId(), ADMIN_EMAIL);
                        return "converted";
                    } catch (RuntimeException e) {
                        return e.getClass().getSimpleName();
                    }
                });
                start.countDown();
                int status = extend.get(30, TimeUnit.SECONDS);
                String converted = convert.get(30, TimeUnit.SECONDS);
                assertThat(status).isEqualTo(200);
                List<Map<String, Object>> current = jdbc.queryForList("select id, source, period_end from business_subscription where business_id = ? and status <> 'EXPIRED'", t.businessId());
                assertThat(current).hasSize(1);
                if (converted.equals("converted")) {
                    // conversion first: the edit (same row version 0) applied to the new manual row, as before this story
                    assertThat(current.get(0)).containsEntry("source", "MANUAL");
                    assertThat(subscription(trialId)).containsEntry("status", "EXPIRED");
                } else {
                    assertThat(converted).isEqualTo("SubscriptionVersionConflictException");
                    assertThat(current.get(0)).containsEntry("source", "TRIAL").containsEntry("id", trialId);
                }
                assertThat(instant(current.get(0).get("period_end"))).isEqualTo(extended);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
