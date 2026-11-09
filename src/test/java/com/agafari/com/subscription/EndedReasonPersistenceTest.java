package com.agafari.com.subscription;

import com.agafari.com.dto.request.TrialConvertRequest;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.GrandfatheringService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R2-06: every way a row ends records why, and a conversion links the old row to its successor. */
class EndedReasonPersistenceTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private TrialExpirer expirer;
    @Autowired private GrandfatheringService grandfathering;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("ended-admin");
        owner = data.tenant("ended-owner");
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

    private String row(String source, String status, String plan, Instant periodEnd) {
        String id = UUID.randomUUID().toString();
        Instant created = days(-40);
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) "
                + "values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 0)", id, owner.businessId(), plan, status, periodEnd == null ? null : Timestamp.from(periodEnd), source, Timestamp.from(created), admin.getId(), Timestamp.from(created), admin.getId());
        return id;
    }

    private String reason(String id) {
        return jdbc.queryForObject("select ended_reason from business_subscription where id = ?", String.class, id);
    }

    @Test
    void anAdminEndRecordsAdmin() throws Exception {
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30));
        mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.adminBearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EXPIRED\",\"rowVersion\":0}")).andExpect(status().isOk());
        assertThat(reason(id)).isEqualTo("ADMIN");
    }

    private void pastDue(String id) {
        jdbc.update("update business_subscription set status = 'PAST_DUE', past_due_since = now() where id = ?", id);
    }

    @Test
    void anAdminEndOfAPastDueRowClearsPastDueSince() throws Exception {
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        // Only a PROVIDER row can be PAST_DUE (TransitionRules); V20 refuses new ones, so the fixture lifts the refusal for this insert.
        // AGF SUB-R4-02: a fixture row has no payment evidence, so the commit check stays off for this test; the per-test reset restores it.
        jdbc.execute("alter table business_subscription disable trigger provider_row_has_evidence");
        String id = row("PROVIDER", "ACTIVE", "MODEL_4", days(30));
        pastDue(id);
        mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.adminBearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EXPIRED\",\"rowVersion\":0}")).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select status, past_due_since, ended_reason from business_subscription where id = ?", id))
                .containsEntry("status", "EXPIRED").containsEntry("past_due_since", null).containsEntry("ended_reason", "ADMIN");
    }

    @Test
    void aPeriodEditRecordsNoReason() throws Exception {
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        String id = row("MANUAL", "ACTIVE", "MODEL_4", days(30));
        mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.adminBearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"periodEnd\":\"" + days(40) + "\",\"rowVersion\":0}")).andExpect(status().isOk());
        assertThat(reason(id)).isNull();
    }

    @Test
    void aConversionRecordsConvertedAndLinksTheSuccessor() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String trial = row("TRIAL", "TRIAL", "MODEL_1", days(20));
        trials.convert(owner.businessId(), new TrialConvertRequest(1, 0, null), admin.getId(), "ended-admin@example.test");
        String successor = jdbc.queryForObject("select id from business_subscription where business_id = ? and status <> 'EXPIRED'", String.class, owner.businessId());
        Map<String, Object> row = jdbc.queryForMap("select ended_reason, replaced_by_id from business_subscription where id = ?", trial);
        assertThat(row).containsEntry("ended_reason", "CONVERTED").containsEntry("replaced_by_id", successor);
        assertThat(reason(successor)).isNull();
    }

    @Test
    void trialExpiryRecordsTrialEndOnTouchAndByTheJob() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String touched = row("TRIAL", "TRIAL", "MODEL_1", days(-1));
        assertThat(expirer.expireDueOnTouch(owner.businessId())).isTrue();
        assertThat(reason(touched)).isEqualTo("TRIAL_END");
        owner = data.tenant("ended-owner-2"); // a restaurant has at most one trial, ever
        String byJob = row("TRIAL", "TRIAL", "MODEL_1", days(-1));
        assertThat(trials.runExpiry()).isEqualTo(1);
        assertThat(reason(byJob)).isEqualTo("TRIAL_END");
    }

    @Test
    void aGrandfatheredEndRecordsGrandfatheredEnd() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        grandfathering.setEnabled(true);
        String id = row("MANUAL", "ACTIVE", "MODEL_3", days(-3));
        assertThat(grandfathering.expireDueOnTouch(owner.businessId())).isTrue();
        assertThat(reason(id)).isEqualTo("GRANDFATHERED_END");
    }
}
