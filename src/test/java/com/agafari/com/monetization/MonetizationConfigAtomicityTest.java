package com.agafari.com.monetization;

import com.agafari.com.security.AdminAuditService;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M2 (AC6): a configuration change and its audit row commit together. If writing the audit row fails, the new
 * version is rolled back too, so there is never an unaudited change.
 */
class MonetizationConfigAtomicityTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void ac6IfTheAuditRowCanNotBeWrittenTheChangeIsRolledBack() throws Exception {
        String admin = data.adminBearer(data.admin("atomic"));
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq("MONETIZATION_CONFIG_CHANGED"), any(), any(), any(), any(), any());

        mockMvc.perform(put("/api/admin/monetization-config").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setting\":\"SETTING_1\",\"models\":[],\"expectedVersion\":0}"))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from monetization_config", Integer.class)).isZero();
    }
}
