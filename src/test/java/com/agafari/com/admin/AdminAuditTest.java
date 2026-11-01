package com.agafari.com.admin;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.security.AdminBootstrap;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M1 (AC9, AC10, SC6, SC7): admin sign-ins are audited (failures persist although the request fails); audit rows
 * never hold passwords, tokens or other secrets; the table is append-only even for direct SQL; the read API is paged.
 */
class AdminAuditTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminBootstrap bootstrap;

    private String login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse().getContentAsString();
    }

    private List<String> actions() {
        return jdbc.queryForList("select action || '/' || outcome from admin_audit_event order by occurred_at", String.class);
    }

    @Test
    void ac9SuccessAndFailureAreAuditedAndFailuresPersist() throws Exception {
        User admin = data.admin("audited");
        login(admin.getEmail(), "Wrong-Passw0rd!");
        login("unknown@admin.agafari.local", "Wrong-Passw0rd!");
        login(admin.getEmail(), TestDataFactory.PASSWORD);

        assertThat(actions()).containsExactly("ADMIN_LOGIN_FAILED/FAILURE", "ADMIN_LOGIN_FAILED/FAILURE", "ADMIN_LOGIN_SUCCEEDED/SUCCESS");
        Map<String, Object> success = jdbc.queryForMap("select actor_user_id, actor_label from admin_audit_event where action = 'ADMIN_LOGIN_SUCCEEDED'");
        assertThat(success.get("actor_user_id")).isEqualTo(admin.getId());
        assertThat((String) success.get("actor_label")).doesNotContain(admin.getEmail()).contains("***");
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'ADMIN_LOGIN_FAILED' and actor_user_id is null", Integer.class))
                .isEqualTo(1); // unknown email: no actor
    }

    @Test
    void sc6AuditRowsNeverContainPasswordsTokensOrRawEmails() throws Exception {
        String bootstrapEmail = "root-" + UUID.randomUUID().toString().substring(0, 8) + "@admin.agafari.local";
        String bootstrapPassword = "Aa1!" + UUID.randomUUID();
        bootstrap.bootstrap(bootstrapEmail, bootstrapPassword, true, false);
        login(bootstrapEmail, "Wrong-Passw0rd!");
        String token = JsonPath.read(login(bootstrapEmail, bootstrapPassword), "$.token");

        List<Map<String, Object>> rows = jdbc.queryForList("select * from admin_audit_event");
        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                String text = String.valueOf(value);
                assertThat(text).doesNotContain(bootstrapPassword).doesNotContain("Wrong-Passw0rd!")
                        .doesNotContain(token).doesNotContain(bootstrapEmail);
            }
        }
    }

    @Test
    void sc7DirectSqlUpdateAndDeleteAreRejected() throws Exception {
        User admin = data.admin("append-only");
        login(admin.getEmail(), TestDataFactory.PASSWORD);
        Map<String, Object> before = jdbc.queryForMap("select * from admin_audit_event");

        assertThatThrownBy(() -> jdbc.update("update admin_audit_event set outcome = 'FAILURE'"))
                .hasMessageContaining("admin_audit_event is append-only");
        assertThatThrownBy(() -> jdbc.update("delete from admin_audit_event"))
                .hasMessageContaining("admin_audit_event is append-only");

        assertThat(jdbc.queryForMap("select * from admin_audit_event")).isEqualTo(before);
    }

    @Test
    void ac10TheReadApiIsPagedNewestFirstAndAdminOnly() throws Exception {
        User admin = data.admin("reader");
        for (int i = 0; i < 3; i++) login(admin.getEmail(), "Wrong-Passw0rd!");
        login(admin.getEmail(), TestDataFactory.PASSWORD);

        mockMvc.perform(get("/api/admin/audit-events").param("size", "2").header("Authorization", data.adminBearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].action").value("ADMIN_LOGIN_SUCCEEDED"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(0));
        mockMvc.perform(get("/api/admin/audit-events").param("size", "101").header("Authorization", data.adminBearer(admin)))
                .andExpect(status().isBadRequest());
    }
}
