package com.agafari.com.plan;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M4 (AC8, AC11): fulfilment is one transaction. If the request's audit row can't be written, neither the
 * subscription nor the request change is kept.
 */
class PlanRequestAtomicityTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        reset(audit);
    }

    @Test
    void ac8IfTheFulfilmentAuditFailsNothingIsKept() throws Exception {
        User admin = data.admin("atomic-fulfil");
        Tenant owner = data.tenant("atomic-fulfil-cafe");
        config(jdbc, 1, "SETTING_2", false, false, true, admin.getId());
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq("PLAN_REQUEST_FULFILLED"), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/admin/plan-requests/{id}/fulfil", id).header("Authorization", data.adminBearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1,\"rowVersion\":0}"))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from plan_request where id = ?", String.class, id)).isEqualTo("PENDING");
    }
}
