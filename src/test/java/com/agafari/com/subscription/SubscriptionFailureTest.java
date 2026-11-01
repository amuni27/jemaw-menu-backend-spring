package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.Entitlements;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M3 (AC4, AC10): failures. An error while computing entitlements falls back to today's behaviour, marked
 * {@code degraded} (an M3 compatibility measure only). If the audit row can't be written, the subscription isn't created.
 */
@ExtendWith(OutputCaptureExtension.class)
class SubscriptionFailureTest extends AbstractIntegrationTest {

    @MockitoSpyBean private BusinessSubscriptionRepository subscriptions;
    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private EntitlementService entitlements;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        entitlements.setEnabled(false);
        reset(subscriptions, audit);
    }

    private User configuredBy() {
        User admin = data.admin("failures");
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, "
                + "created_at, created_by_user_id) values (1, 'SETTING_2', true, false, false, now(), now(), ?)", admin.getId());
        return admin;
    }

    @Test
    void ac4AnErrorWhileComputingEntitlementsFallsBackToUnrestrictedAndIsMarkedDegraded(CapturedOutput output) {
        configuredBy();
        entitlements.setEnabled(true);
        Tenant t = data.tenant("degraded");
        doThrow(new IllegalStateException("database unavailable")).when(subscriptions).findCurrentByBusinessId(anyString());

        Entitlements e = entitlements.forBusiness(t.businessId());

        assertThat(e.degraded()).isTrue();
        assertThat(e.mode()).isEqualTo(Entitlements.Mode.INACTIVE);
        assertThat(e.adSource()).isEqualTo(Entitlements.AdSource.NONE);
        assertThat(e.limits()).isEmpty();
        assertThat(output.getAll()).contains("event=entitlements.degraded");
    }

    @Test
    void ac10IfTheAuditRowCanNotBeWrittenNoSubscriptionIsCreated() throws Exception {
        User admin = configuredBy();
        Tenant t = data.tenant("atomic-sub");
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq("SUBSCRIPTION_CREATED"), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", data.adminBearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}"))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
    }
}
