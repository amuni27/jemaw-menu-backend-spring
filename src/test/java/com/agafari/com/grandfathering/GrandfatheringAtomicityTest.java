package com.agafari.com.grandfathering;

import com.agafari.com.security.AdminAuditService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;

import static com.agafari.com.grandfathering.GrandfatheringFixtures.manual;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF E4-M6 (AC11b): a transition date and its version record commit together; if the record can't be written, no date is stored. */
class GrandfatheringAtomicityTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        reset(audit);
    }

    @Test
    void ac11bWhenTheVersionRecordFailsTheTransitionDateIsNotStored() throws Exception {
        String adminId = data.admin("gf-atomic").getId();
        config(jdbc, 1, "SETTING_2", true, true, true, adminId);
        Tenant t = data.tenant("gf-atomic");
        String id = manual(jdbc, t.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(5)), adminId);
        config(jdbc, 2, "SETTING_2", true, false, true, adminId);
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq("SUBSCRIPTION_TRANSITION_SET"), any(), any(), any(), any(), any());

        mockMvc.perform(put("/api/admin/businesses/{id}/subscription/transition-date", t.businessId())
                .header("Authorization", data.adminBearer(data.admin("gf-atomic-2"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transitionDate\":\"2027-06-30\",\"rowVersion\":0}")).andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select transition_date is null from business_subscription where id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select row_version from business_subscription where id = ?", Integer.class, id)).isZero();
    }
}
