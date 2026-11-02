package com.agafari.com.restaurantads;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.RestaurantAdService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.agafari.com.restaurantads.RestaurantAdTestSupport.ADMIN;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.FIELD;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.action;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.createId;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.list;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.reinstate;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.suspend;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.update;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.valid;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.with;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M8 (AC9, Q-M8-2): post-publication oversight. Only the Super Admin suspends (ACTIVE or PAUSED, a 1–500 character
 * reason, audited with the reason) and reinstates (SUSPENDED → PAUSED, audited). A suspended ad isn't served; its owner sees
 * the reason, may edit its content and archive it, but can never pause, resume or otherwise reactivate it. The Super Admin
 * can't edit content, create or archive restaurant ads, and oversight works whatever the flags.
 */
class RestaurantAdSuspensionTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementService entitlements;
    @Autowired private RestaurantAdService restaurantAds;

    private User admin;
    private String adminBearer;
    private Tenant owner;
    private String ownerBearer;

    @BeforeEach
    void setUp() {
        admin = data.admin("ra-suspend-admin");
        adminBearer = data.adminBearer(admin);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        entitlements.setEnabled(true);
        restaurantAds.setEnabled(true);
        owner = data.tenant("ra-suspend-owner");
        subscription(jdbc, owner.businessId(), "MODEL_3", 1, admin.getId());
        ownerBearer = data.bearer(owner);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        restaurantAds.setEnabled(false);
    }

    private int audits(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ?", Integer.class, action);
    }

    private String auditDetail(String action) {
        return jdbc.queryForObject("select detail from admin_audit_event where action = ? order by occurred_at desc limit 1", String.class, action);
    }

    @Test
    void ac9TheSuperAdminSuspendsWithAReasonAndTheOwnerCanEditOrArchiveButNeverReactivate() throws Exception {
        String id = createId(mockMvc, ownerBearer, valid("Big sale"));
        suspend(mockMvc, adminBearer, id, null, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("reason")).value(hasItem("Give a reason (at most 500 characters)")));
        suspend(mockMvc, adminBearer, id, "   ", 0).andExpect(status().isBadRequest());
        suspend(mockMvc, adminBearer, id, "x".repeat(501), 0).andExpect(status().isBadRequest());
        assertThat(audits("RESTAURANT_AD_ADMIN_REJECTED")).isEqualTo(3);

        suspend(mockMvc, adminBearer, id, "  Misleading price  ", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspensionReason").value("Misleading price"))
                .andExpect(jsonPath("$.suspendedAt").exists());
        assertThat(audits("RESTAURANT_AD_SUSPENDED")).isEqualTo(1);
        assertThat(auditDetail("RESTAURANT_AD_SUSPENDED")).contains("Misleading price");

        list(mockMvc, ownerBearer).andExpect(jsonPath("$.ads[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.ads[0].suspensionReason").value("Misleading price"));
        action(mockMvc, ownerBearer, id, "pause", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_SUSPENDED"));
        action(mockMvc, ownerBearer, id, "resume", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_SUSPENDED"));
        update(mockMvc, ownerBearer, id, with(valid("Fixed sale"), "rowVersion", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fixed sale"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspensionReason").value("Misleading price"));
        assertThat(audits("RESTAURANT_AD_CHANGE_REJECTED")).isEqualTo(2);

        suspend(mockMvc, adminBearer, id, "Again", 2).andExpect(status().isBadRequest()); // already suspended
        reinstate(mockMvc, adminBearer, id, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_VERSION_CONFLICT"));
        reinstate(mockMvc, adminBearer, id, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.suspensionReason").doesNotExist());
        assertThat(audits("RESTAURANT_AD_REINSTATED")).isEqualTo(1);
        reinstate(mockMvc, adminBearer, id, 3).andExpect(status().isBadRequest()); // not suspended
        action(mockMvc, ownerBearer, id, "resume", 3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")); // the owner decides

        // a PAUSED ad can be suspended too, and the owner may archive a suspended ad; archived is final
        action(mockMvc, ownerBearer, id, "pause", 4).andExpect(status().isOk());
        suspend(mockMvc, adminBearer, id, "Complaint", 5).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        action(mockMvc, ownerBearer, id, "archive", 6).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.suspensionReason").doesNotExist());
        suspend(mockMvc, adminBearer, id, "Late", 7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_ARCHIVED"));
        reinstate(mockMvc, adminBearer, id, 7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_ARCHIVED"));
    }

    @Test
    void ac9TheSuperAdminCantEditCreateOrArchiveRestaurantAdsAndSeesAllRestaurants() throws Exception {
        String id = createId(mockMvc, ownerBearer, valid("Owner content"));
        Tenant other = data.tenant("ra-suspend-other");
        subscription(jdbc, other.businessId(), "MODEL_3", 1, admin.getId());
        createId(mockMvc, data.bearer(other), valid("Other content"));

        mockMvc.perform(put(ADMIN + "/{id}", id).header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content(with(valid("Admin edit"), "rowVersion", 0))).andExpect(status().is4xxClientError());
        mockMvc.perform(post(ADMIN).header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON).content(valid("Admin ad")))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post(ADMIN + "/{id}/archive", id).header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":0}")).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForMap("select title, status from restaurant_ad where id = ?", id)).containsEntry("title", "Owner content").containsEntry("status", "ACTIVE");

        mockMvc.perform(get(ADMIN).header("Authorization", adminBearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.title == 'Owner content')].businessName").value(hasItem(owner.business().getName())));
        mockMvc.perform(get(ADMIN).param("businessId", other.businessId()).header("Authorization", adminBearer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("Other content"));
        suspend(mockMvc, adminBearer, id, "Check", 0).andExpect(status().isOk());
        mockMvc.perform(get(ADMIN).param("status", "SUSPENDED").header("Authorization", adminBearer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(id));
        suspend(mockMvc, adminBearer, "no-such-ad", "Check", 0).andExpect(status().isNotFound());
    }

    @Test
    void ac9OversightWorksWhateverTheFlagsAndOwnerTokensCantUseIt() throws Exception {
        String id = createId(mockMvc, ownerBearer, valid("Flags off"));
        restaurantAds.setEnabled(false);
        entitlements.setEnabled(false);
        suspend(mockMvc, adminBearer, id, "Takedown", 0).andExpect(status().isOk());
        suspend(mockMvc, ownerBearer, id, "Self", 1).andExpect(status().isForbidden());
        reinstate(mockMvc, ownerBearer, id, 1).andExpect(status().isForbidden());
        reinstate(mockMvc, adminBearer, id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
    }
}
