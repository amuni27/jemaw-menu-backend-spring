package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.Entitlements;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M3 (AC2, AC3, AC8): one central entitlement check. With the activation flag off (default) every restaurant gets
 * today's behaviour. With it on, entitlements follow the plan of the current subscription; restaurants without one, or
 * when monetization isn't configured, stay unrestricted. Nothing changes automatically (no scheduler; a passed period
 * end has no effect). Specials are never part of entitlements (E4-Q9).
 */
class EntitlementServiceTest extends AbstractIntegrationTest {

    @Autowired private EntitlementService entitlements;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("entitlements");
    }

    @AfterEach
    void flagOff() {
        entitlements.setEnabled(false);
    }

    private void configure(int version, String setting, boolean m2, boolean m3, boolean m4) {
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, "
                + "created_at, created_by_user_id) values (?, ?, ?, ?, ?, now(), now(), ?)", version, setting, m2, m3, m4, admin.getId());
    }

    private void subscribe(Tenant t, String plan, String status, String periodEnd) {
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, ended_at, row_version) values (?, ?, ?, ?, 1, " + periodEnd + ", 'MANUAL', "
                + "now() - interval '10 days', ?, now(), ?, " + (status.equals("EXPIRED") ? "now()" : "null") + ", 0)",
                UUID.randomUUID().toString(), t.businessId(), plan, status, admin.getId(), admin.getId());
    }

    @Test
    void ac2TheActivationFlagIsOffByDefaultAndThenEveryRestaurantIsUnrestricted() {
        assertThat(environment.getProperty("app.monetization.entitlements-enabled", Boolean.class, false)).isFalse();
        assertThat(entitlements.isEnabled()).isFalse();
        configure(1, "SETTING_2", true, false, false);
        Tenant t = data.tenant("flag-off");
        subscribe(t, "MODEL_2", "ACTIVE", "null");

        assertThat(entitlements.forBusiness(t.businessId())).isEqualTo(Entitlements.unrestricted());
        assertThat(Entitlements.unrestricted().mode()).isEqualTo(Entitlements.Mode.INACTIVE);
        assertThat(Entitlements.unrestricted().adSource()).isEqualTo(Entitlements.AdSource.NONE);
        assertThat(Entitlements.unrestricted().limits()).isEmpty();
        assertThat(Entitlements.unrestricted().degraded()).isFalse();
    }

    @Test
    void ac3WithTheFlagOnRestaurantsStayUnrestrictedUntilConfiguredAndSubscribed() {
        entitlements.setEnabled(true);
        Tenant t = data.tenant("not-configured");
        assertThat(entitlements.forBusiness(t.businessId())).isEqualTo(Entitlements.unrestricted()); // not configured

        configure(1, "SETTING_2", true, true, true);
        assertThat(entitlements.forBusiness(t.businessId())).isEqualTo(Entitlements.unrestricted()); // no subscription

        subscribe(t, "MODEL_2", "EXPIRED", "null");
        assertThat(entitlements.forBusiness(t.businessId())).isEqualTo(Entitlements.unrestricted()); // only ended history
    }

    @Test
    void ac3TheCurrentSubscriptionsPlanDecidesTheAdSourceAndTheModel3AdManager() {
        entitlements.setEnabled(true);
        configure(1, "SETTING_2", true, true, true);
        Map<String, Entitlements.AdSource> expected = Map.of("MODEL_2", Entitlements.AdSource.AGAFARI,
                "MODEL_3", Entitlements.AdSource.RESTAURANT, "MODEL_4", Entitlements.AdSource.NONE);
        for (var plan : expected.entrySet()) {
            Tenant t = data.tenant("plan-" + plan.getKey().toLowerCase().replace('_', '-'));
            subscribe(t, plan.getKey(), "ACTIVE", "null");
            Entitlements e = entitlements.forBusiness(t.businessId());
            assertThat(e.mode()).as(plan.getKey()).isEqualTo(Entitlements.Mode.ACTIVE);
            assertThat(e.planCode()).isEqualTo(plan.getKey());
            assertThat(e.status()).isEqualTo("ACTIVE");
            assertThat(e.adSource()).isEqualTo(plan.getValue());
            assertThat(e.restaurantAdManager()).isEqualTo(plan.getKey().equals("MODEL_3"));
            assertThat(e.limits()).isEmpty(); // E4-Q3 deferred
            assertThat(e.source()).isEqualTo("MANUAL");
            assertThat(e.degraded()).isFalse();
        }
    }

    @Test
    void ac3TrialAndGrandfatheredRowsWouldBeActiveSoLaterStoriesKeepTheContract() {
        entitlements.setEnabled(true);
        configure(1, "SETTING_1", false, false, false);
        Tenant trial = data.tenant("trial-row");
        subscribe(trial, "MODEL_1", "TRIAL", "null");
        assertThat(entitlements.forBusiness(trial.businessId()).mode()).isEqualTo(Entitlements.Mode.ACTIVE);
        Tenant grand = data.tenant("grandfathered-row");
        subscribe(grand, "MODEL_1", "GRANDFATHERED", "null");
        assertThat(entitlements.forBusiness(grand.businessId()).status()).isEqualTo("GRANDFATHERED");
    }

    @Test
    void ac8APassedPeriodEndChangesNeitherTheRowNorTheEntitlements() {
        entitlements.setEnabled(true);
        configure(1, "SETTING_2", false, false, true);
        Tenant t = data.tenant("period-passed");
        subscribe(t, "MODEL_4", "ACTIVE", "now() - interval '1 day'");
        Map<String, Object> before = jdbc.queryForMap("select * from business_subscription where business_id = ?", t.businessId());

        Entitlements e = entitlements.forBusiness(t.businessId());

        assertThat(e.mode()).isEqualTo(Entitlements.Mode.ACTIVE);
        assertThat(e.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("select * from business_subscription where business_id = ?", t.businessId())).isEqualTo(before);
    }

    @Test
    void ac8NothingInM3RunsOnASchedule() throws Exception {
        for (String name : List.of("com.agafari.com.service.EntitlementService", "com.agafari.com.service.SubscriptionService")) {
            Method[] methods = Class.forName(name).getDeclaredMethods();
            assertThat(Arrays.stream(methods).filter(m -> m.isAnnotationPresent(Scheduled.class))).as(name).isEmpty();
        }
    }

    @Test
    void specialsStayAvailableForEveryModel() throws Exception {
        entitlements.setEnabled(true);
        configure(1, "SETTING_2", true, true, true);
        for (String plan : List.of("MODEL_2", "MODEL_3", "MODEL_4")) {
            Tenant t = data.tenant("specials-" + plan.toLowerCase().replace('_', '-'));
            subscribe(t, plan, "ACTIVE", "null");
            mockMvc.perform(get("/api/specials").header("Authorization", data.bearer(t))).andExpect(status().isOk());
            mockMvc.perform(get("/api/category-specials").header("Authorization", data.bearer(t))).andExpect(status().isOk());
        }
    }

    @Test
    void theAdminViewShowsTheComputedEntitlementsAndWhetherMonetizationIsActive() throws Exception {
        configure(1, "SETTING_2", false, true, false);
        Tenant t = data.tenant("admin-view");
        subscribe(t, "MODEL_3", "ACTIVE", "null");
        String bearer = data.adminBearer(admin);
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.entitlements.mode").value("INACTIVE"))
                .andExpect(jsonPath("$.monetizationActive").value(false));
        entitlements.setEnabled(true);
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.entitlements.mode").value("ACTIVE"))
                .andExpect(jsonPath("$.entitlements.adSource").value("RESTAURANT"))
                .andExpect(jsonPath("$.entitlements.restaurantAdManager").value(true))
                .andExpect(jsonPath("$.entitlements.source").value("MANUAL"))
                .andExpect(jsonPath("$.monetizationActive").value(true));
    }
}
