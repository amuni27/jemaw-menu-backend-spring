package com.agafari.com.restaurantads;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.RestaurantAdService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.agafari.com.restaurantads.RestaurantAdTestSupport.OWNER;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.action;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.create;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.list;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.update;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.valid;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.with;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M8 (AC10, Q-M8-9): when the restaurant isn't on a current Model 3 plan (Models 1, 2, 4, none, ended, grandfathered
 * past its approved end) or restaurant ads or entitlements are off, the owner may only read and archive: create, edit,
 * pause, resume and image changes are refused in the service (403 RESTAURANT_ADS_NOT_ENTITLED) and audited.
 */
class RestaurantAdOwnerRestrictionTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementService entitlements;
    @Autowired private RestaurantAdService restaurantAds;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private MonetizationClock clock;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("ra-restrict-admin");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        entitlements.setEnabled(true);
        restaurantAds.setEnabled(true);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        restaurantAds.setEnabled(false);
        grandfathering.setEnabled(false);
        clock.reset();
    }

    private String seedAd(String businessId, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into restaurant_ad (id, business_id, title, body, link_url, starts_at, schedule_time_zone, status, created_at, created_by, "
                + "updated_at, updated_by, row_version) values (?, ?, 'Seeded', 'Text', 'https://e.test', now(), 'Africa/Addis_Ababa', ?, now(), 'o', now(), 'o', 0)",
                id, businessId, status);
        return id;
    }

    private ResultActions post(String bearer, String path, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(OWNER + path).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Every write but archive is 403; read works (canManage false); archive works. */
    private void readAndArchiveOnly(Tenant t, String label) throws Exception {
        String bearer = data.bearer(t);
        String active = seedAd(t.businessId(), "ACTIVE"), paused = seedAd(t.businessId(), "PAUSED");
        int before = jdbc.queryForObject("select count(*) from admin_audit_event where action = 'RESTAURANT_AD_CHANGE_REJECTED' and detail = 'not-entitled'", Integer.class);
        list(mockMvc, bearer).andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.ads[?(@.id == '" + active + "')].status").value(org.hamcrest.Matchers.hasItem("ACTIVE")));
        create(mockMvc, bearer, valid("New " + label)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESTAURANT_ADS_NOT_ENTITLED"));
        update(mockMvc, bearer, active, with(valid("Edit " + label), "rowVersion", 0)).andExpect(status().isForbidden());
        action(mockMvc, bearer, active, "pause", 0).andExpect(status().isForbidden());
        action(mockMvc, bearer, paused, "resume", 0).andExpect(status().isForbidden());
        post(bearer, "/" + active + "/image/presign", "{\"contentType\":\"image/png\"}").andExpect(status().isForbidden());
        post(bearer, "/" + active + "/image/confirm", "{\"objectKey\":\"restaurant-ad-asset/" + t.businessId() + "/" + active + "/main.png\",\"imageAlt\":\"x\",\"rowVersion\":0}")
                .andExpect(status().isForbidden());
        action(mockMvc, bearer, active, "image/remove", 0).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'RESTAURANT_AD_CHANGE_REJECTED' and detail = 'not-entitled'", Integer.class))
                .as(label).isEqualTo(before + 7);
        assertThat(jdbc.queryForMap("select title, status, row_version from restaurant_ad where id = ?", active))
                .containsEntry("title", "Seeded").containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        action(mockMvc, bearer, active, "archive", 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        action(mockMvc, bearer, paused, "archive", 0).andExpect(status().isOk());
    }

    @Test
    void ac10OtherModelsAndNoSubscriptionMayOnlyReadAndArchive() throws Exception {
        for (String plan : new String[] {"MODEL_1", "MODEL_2", "MODEL_4", null}) {
            Tenant t = data.tenant("ra-restrict-" + (plan == null ? "none" : plan.toLowerCase().replace('_', '-')));
            if (plan != null) subscription(jdbc, t.businessId(), plan, 1, admin.getId());
            readAndArchiveOnly(t, String.valueOf(plan));
        }
    }

    @Test
    void ac10AnEndedModel3MayOnlyReadAndArchive() throws Exception {
        Tenant t = data.tenant("ra-restrict-ended");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, "
                + "updated_by_user_id, ended_at, row_version) values (?, ?, 'MODEL_3', 'EXPIRED', 1, 'MANUAL', now() - interval '5 days', ?, now(), ?, now(), 0)",
                UUID.randomUUID().toString(), t.businessId(), admin.getId(), admin.getId());
        readAndArchiveOnly(t, "ended");
    }

    @Test
    void ac10AGrandfatheredModel3ManagesUntilItsApprovedEndThenReadAndArchiveOnly() throws Exception {
        Tenant t = data.tenant("ra-restrict-grandfathered");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_3', 'ACTIVE', 1, ?, 'MANUAL', now() - interval '30 days', ?, now(), ?, 0)",
                UUID.randomUUID().toString(), t.businessId(), Timestamp.from(Instant.now().plus(Duration.ofDays(2))), admin.getId(), admin.getId());
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId()); // Model 3 no longer offered
        grandfathering.setEnabled(true);
        create(mockMvc, data.bearer(t), valid("Before end")).andExpect(status().isCreated());
        clock.setOffset(Duration.ofDays(3)); // past its approved end: effectively ended (existing M6 behaviour)
        readAndArchiveOnly(t, "grandfathered");
    }

    @Test
    void ac10WithRestaurantAdsOrEntitlementsOffAModel3OwnerMayOnlyReadAndArchive() throws Exception {
        Tenant t = data.tenant("ra-restrict-flags");
        subscription(jdbc, t.businessId(), "MODEL_3", 1, admin.getId());
        restaurantAds.setEnabled(false);
        readAndArchiveOnly(t, "ads flag off");
        restaurantAds.setEnabled(true);
        entitlements.setEnabled(false);
        readAndArchiveOnly(t, "entitlements off");
    }
}
