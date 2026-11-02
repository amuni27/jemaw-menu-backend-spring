package com.agafari.com.restaurantads;

import com.agafari.com.jpa.repository.RestaurantAdRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.RestaurantAdService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static com.agafari.com.restaurantads.RestaurantAdTestSupport.action;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.create;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.list;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.valid;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M8 (AC4, AC10, §5): fail closed. Degraded entitlements (the M3 fail-open answer has no restaurant ad manager) mean
 * no restaurant ad and no notice on the guest menu, and read-and-archive only for the owner; an error while selecting an
 * ad means the menu is served without one. The guest menu is always 200.
 */
class RestaurantAdFailSafeTest extends AbstractIntegrationTest {

    @MockitoSpyBean private SubscriptionPlanRepository plans;
    @MockitoSpyBean private RestaurantAdRepository ads;
    @Autowired private RestaurantAdService restaurantAds;
    @Autowired private EntitlementService entitlements;
    @Autowired private JdbcTemplate jdbc;

    private Tenant t;
    private String adId;

    @BeforeEach
    void setUp() {
        var admin = data.admin("ra-failsafe");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        t = data.tenant("ra-failsafe");
        subscription(jdbc, t.businessId(), "MODEL_3", 1, admin.getId());
        adId = UUID.randomUUID().toString();
        jdbc.update("insert into restaurant_ad (id, business_id, title, body, link_url, starts_at, schedule_time_zone, status, created_at, created_by, "
                + "updated_at, updated_by, row_version) values (?, ?, 'Fail safe', 'Text', 'https://e.test', '2026-01-01T00:00:00Z', 'Africa/Addis_Ababa', 'ACTIVE', "
                + "now(), 'o', now(), 'o', 0)", adId, t.businessId());
        entitlements.setEnabled(true);
        restaurantAds.setEnabled(true);
    }

    @AfterEach
    void restore() {
        entitlements.setEnabled(false);
        restaurantAds.setEnabled(false);
        reset(plans, ads);
    }

    private String menu() throws Exception {
        return mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void degradedEntitlementsMeanNoAdNoNoticeAndReadAndArchiveOnly() throws Exception {
        assertThat(menu()).contains("sponsored").contains("restaurantAdsNotice");
        doThrow(new IllegalStateException("plans unavailable")).when(plans).findById(anyString());
        assertThat(menu()).doesNotContain("sponsored").doesNotContain("restaurantAdsNotice");
        String bearer = data.bearer(t);
        list(mockMvc, bearer).andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false));
        create(mockMvc, bearer, valid("Degraded")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESTAURANT_ADS_NOT_ENTITLED"));
        action(mockMvc, bearer, adId, "pause", 0).andExpect(status().isForbidden());
        action(mockMvc, bearer, adId, "archive", 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void anErrorWhileSelectingMeansTheMenuIsServedWithoutAnAd() throws Exception {
        doThrow(new IllegalStateException("ads unavailable")).when(ads).findServable(anyString(), any());
        assertThat(menu()).doesNotContain("sponsored");
    }
}
