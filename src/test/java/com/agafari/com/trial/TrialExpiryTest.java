package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 (AC5, AC6, AC9a; D-M5-4, D-M5-13): the daily job expires due trials exactly once (idempotent, catches up,
 * one run at a time). Expiry changes nothing for owners or guests. Every read reports the effective state before the job
 * has run; reads never write.
 */
class TrialExpiryTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private PlanRequestService planRequests;
    @Autowired private MonetizationClock clock;
    @Autowired private DataSource dataSource;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-expiry");
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.setEnabled(true);
    }

    @AfterEach
    void reset() {
        trials.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        clock.reset();
    }

    private String trial(Tenant t, int daysAgoStarted, int lengthDays) {
        String id = UUID.randomUUID().toString();
        Instant start = Instant.now().minus(Duration.ofDays(daysAgoStarted));
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                id, t.businessId(), Timestamp.from(start.plus(Duration.ofDays(lengthDays))), Timestamp.from(start), Timestamp.from(start));
        return id;
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("select status from business_subscription where id = ?", String.class, id);
    }

    private int expiredAudits() {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = 'TRIAL_EXPIRED'", Integer.class);
    }

    @Test
    void ac5TheJobExpiresOnlyDueTrialsOnceAndCatchesUp() {
        String due1 = trial(data.tenant("due-one"), 40, 30);
        String due2 = trial(data.tenant("due-two"), 31, 30); // missed by a day
        String running = trial(data.tenant("still-running"), 5, 30);

        assertThat(trials.runExpiry()).isEqualTo(2);
        assertThat(statusOf(due1)).isEqualTo("EXPIRED");
        assertThat(statusOf(due2)).isEqualTo("EXPIRED");
        assertThat(statusOf(running)).isEqualTo("TRIAL");
        assertThat(jdbc.queryForObject("select ended_at = period_end from business_subscription where id = ?", Boolean.class, due1)).isTrue();
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'TRIAL_EXPIRED' limit 1", String.class)).contains("job");

        assertThat(trials.runExpiry()).isZero(); // idempotent
        assertThat(expiredAudits()).isEqualTo(2);

        clock.setOffset(Duration.ofDays(26)); // the running trial becomes due
        assertThat(trials.runExpiry()).isEqualTo(1);
        assertThat(statusOf(running)).isEqualTo("EXPIRED");
    }

    @Test
    void ac5ASecondInstanceSkipsWhileOneRunIsInProgress() throws Exception {
        trial(data.tenant("locked-run"), 40, 30);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement s = other.createStatement()) {
                s.execute("select pg_advisory_xact_lock(" + TrialService.TRIAL_JOB_LOCK_KEY + ")"); // another instance is running
            }
            assertThat(trials.runExpiry()).isEqualTo(-1); // skipped, nothing touched
            other.rollback();
        }
        assertThat(expiredAudits()).isZero();
        assertThat(trials.runExpiry()).isEqualTo(1);
    }

    @Test
    void ac5TheJobDoesNothingWhileTrialsAreDisabledAndIsTheOnlyScheduledMethod() throws Exception {
        trial(data.tenant("disabled-run"), 40, 30);
        trials.setEnabled(false);
        assertThat(trials.runExpiry()).isZero();
        assertThat(expiredAudits()).isZero();
        Method[] methods = Class.forName("com.agafari.com.service.TrialExpiryJob").getDeclaredMethods();
        assertThat(Arrays.stream(methods).filter(m -> m.isAnnotationPresent(Scheduled.class))).hasSize(1);
    }

    @Test
    void ac6ExpiryChangesNothingForOwnersOrGuests() throws Exception {
        Tenant t = data.tenant("expiry-unaffected");
        trial(t, 40, 30);
        String menus = mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString();
        String guest = mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andReturn().getResponse().getContentAsString();
        Map<String, String> before = fingerprint(); // after the reads: guest menu opens are counted (S2-24)

        trials.runExpiry();

        assertThat(fingerprint()).isEqualTo(before);
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(t))).andExpect(status().isOk());
        assertThat(mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString()).isEqualTo(menus);
        assertThat(mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andReturn().getResponse().getContentAsString()).isEqualTo(guest);
    }

    @Test
    void ac9aReadsReportTheEffectiveStateBeforeTheJobAndNeverWrite() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        Tenant t = data.tenant("effective");
        trial(t, 10, 30);
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.trial.effectiveStatus").value("TRIAL"))
                .andExpect(jsonPath("$.trial.daysLeft").value(20))
                .andExpect(jsonPath("$.trialAvailable").value(true));

        clock.setOffset(Duration.ofDays(21)); // period end passed, job not run
        Map<String, String> before = fingerprintWithMonetization();
        String ownerBefore = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.trial.effectiveStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.trial.daysLeft").value(0))
                .andExpect(jsonPath("$.trial.status").doesNotExist()) // the owner never sees the persisted status
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", data.adminBearer(admin)))
                .andExpect(jsonPath("$.current.status").value("TRIAL"))
                .andExpect(jsonPath("$.current.effectiveStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.current.sourceLabel").value("Trial (automatic), no payment involved"));
        assertThat(fingerprintWithMonetization()).isEqualTo(before); // GETs wrote nothing

        trials.runExpiry();
        String ownerAfter = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString();
        assertThat(ownerAfter).isEqualTo(ownerBefore); // identical before and after the job
    }

    private Map<String, String> fingerprint() {
        return hashes("and table_name not in ('monetization_config', 'admin_audit_event', 'business_subscription', 'plan_request')");
    }

    private Map<String, String> fingerprintWithMonetization() {
        return hashes("");
    }

    private Map<String, String> hashes(String filter) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' "
                + filter + " order by 1", String.class)) {
            result.put(table, jdbc.queryForObject("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from \"" + table + "\" t", String.class));
        }
        return result;
    }
}
