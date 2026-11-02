package com.agafari.com.trial;

import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.trial.TrialTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 (AC2, D-M5-3): the restaurant and its trial commit together or not at all, so there is never a registered
 * restaurant that is owed a trial.
 */
class TrialRegistrationAtomicityTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private TrialService trials;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        trials.setEnabled(false);
        reset(audit);
    }

    @Test
    void ac2AFailingTrialRollsBackTheWholeRegistration() throws Exception {
        config(jdbc, 1, "SETTING_1", false, false, false, data.admin("atomic-trial").getId());
        trials.setEnabled(true);
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq("TRIAL_STARTED"), any(), any(), any(), any(), any());

        register(mockMvc, "atomic-trial").andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from users where email like 'atomic-trial-%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
    }
}
