package com.agafari.com.ads;

import com.agafari.com.jpa.repository.AgafariAdRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.service.AgafariAdDelivery;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.agafari.com.ads.AdTestSupport.body;
import static com.agafari.com.ads.AdTestSupport.createId;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M7 (AC5, §5): fail-safe. Degraded entitlements (the existing M3 fail-open result) mean no ad; an error while
 * selecting an ad means the menu is served without one. In both cases the guest menu is still 200.
 */
class AdDeliveryFailSafeTest extends AbstractIntegrationTest {

    @MockitoSpyBean private SubscriptionPlanRepository plans;
    @MockitoSpyBean private AgafariAdRepository ads;
    @Autowired private AgafariAdDelivery delivery;
    @Autowired private EntitlementService entitlements;
    @Autowired private JdbcTemplate jdbc;

    private Tenant t;

    @BeforeEach
    void setUp() throws Exception {
        var admin = data.admin("ads-failsafe");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        createId(mockMvc, data.adminBearer(admin), body("Fail safe", "Text", "https://example.test", "2026-01-01T00:00", null, "UTC"));
        t = data.tenant("ads-failsafe");
        subscription(jdbc, t.businessId(), "MODEL_2", 1, admin.getId());
        entitlements.setEnabled(true);
        delivery.setEnabled(true);
    }

    @AfterEach
    void restore() {
        entitlements.setEnabled(false);
        delivery.setEnabled(false);
        reset(plans, ads);
    }

    private String menu() throws Exception {
        return mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void degradedEntitlementsMeanNoAd() throws Exception {
        assertThat(menu()).contains("sponsored");
        doThrow(new IllegalStateException("plans unavailable")).when(plans).findById(anyString());
        assertThat(menu()).doesNotContain("sponsored");
    }

    @Test
    void anErrorWhileSelectingMeansTheMenuIsServedWithoutAnAd() throws Exception {
        doThrow(new IllegalStateException("ads unavailable")).when(ads).findServable(any());
        assertThat(menu()).doesNotContain("sponsored");
        assertThat(jdbc.queryForObject("select count(*) from ad_impression_daily", Integer.class)).isZero();
    }
}
