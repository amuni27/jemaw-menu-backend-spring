package com.agafari.com.monetization;

import com.agafari.com.enums.UserRole;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M2 (AC1–AC7, AC9, AC10): the Super Admin chooses one platform-wide monetization configuration. Setting 1 is
 * Model 1 only; Setting 2 is any nonempty combination of Models 2, 3 and 4. Every change is a new immutable version,
 * audited, and touches no restaurant data (grandfathering is preserved by design; the transition itself is E4-M6).
 */
class MonetizationConfigTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/admin/monetization-config";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;

    private ResultActions save(String bearer, String body) throws Exception {
        return mockMvc.perform(put(PATH).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String setting, String models, int expectedVersion) {
        return "{\"setting\":\"" + setting + "\",\"models\":" + models + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private int versions() {
        return jdbc.queryForObject("select count(*) from monetization_config", Integer.class);
    }

    private List<String> audit() {
        return jdbc.queryForList("select action || '/' || outcome || '/' || coalesce(detail, '') from admin_audit_event "
                + "where action like 'MONETIZATION%' order by occurred_at", String.class);
    }

    @Test
    void ac1BeforeTheFirstSaveTheConfigurationIsNotConfigured() throws Exception {
        String admin = data.adminBearer(data.admin("first-look"));
        mockMvc.perform(get(PATH).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.version").doesNotExist());
        assertThat(versions()).isZero();
    }

    @Test
    void ac2Setting1IsModel1OnlyAndCanBeSavedWithOrWithoutListingModel1() throws Exception {
        User adminUser = data.admin("setting-one");
        String admin = data.adminBearer(adminUser);
        save(admin, body("SETTING_1", "[]", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.setting").value("SETTING_1"))
                .andExpect(jsonPath("$.models.length()").value(1))
                .andExpect(jsonPath("$.models[0]").value("MODEL_1"))
                .andExpect(jsonPath("$.changedBy").value("s***@admin.agafari.local"));
        save(admin, body("SETTING_2", "[\"MODEL_4\"]", 1)).andExpect(status().isOk());
        save(admin, body("SETTING_1", "[\"MODEL_1\"]", 2)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(get(PATH).header("Authorization", admin))
                .andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.setting").value("SETTING_1"));
    }

    @Test
    void ac2Setting1WithAnyOtherModelIsRejected() throws Exception {
        String admin = data.adminBearer(data.admin("setting-one-bad"));
        for (String models : List.of("[\"MODEL_2\"]", "[\"MODEL_1\",\"MODEL_3\"]", "[\"MODEL_4\"]")) {
            save(admin, body("SETTING_1", models, 0))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("models"));
        }
        assertThat(versions()).isZero();
    }

    @Test
    void ac3EveryNonemptyCombinationOfModels2To4IsAcceptedUnderSetting2() throws Exception {
        String admin = data.adminBearer(data.admin("combinations"));
        List<String> combinations = List.of("[\"MODEL_2\"]", "[\"MODEL_3\"]", "[\"MODEL_4\"]", "[\"MODEL_2\",\"MODEL_3\"]",
                "[\"MODEL_2\",\"MODEL_4\"]", "[\"MODEL_3\",\"MODEL_4\"]", "[\"MODEL_4\",\"MODEL_3\",\"MODEL_2\"]");
        int version = 0;
        for (String models : combinations) {
            save(admin, body("SETTING_2", models, version)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version + 1));
            version++;
        }
        mockMvc.perform(get(PATH).header("Authorization", admin))
                .andExpect(jsonPath("$.models.length()").value(3))
                .andExpect(jsonPath("$.models[0]").value("MODEL_2")) // always reported in model order
                .andExpect(jsonPath("$.models[2]").value("MODEL_4"));
        assertThat(versions()).isEqualTo(7);
    }

    @Test
    void ac3InvalidShapesAreRejectedWithFieldErrorsAndAuditedWithoutAVersion() throws Exception {
        String admin = data.adminBearer(data.admin("invalid"));
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put(body("SETTING_2", "[]", 0), "models");
        cases.put(body("SETTING_2", "[\"MODEL_1\",\"MODEL_2\"]", 0), "models");
        cases.put(body("SETTING_2", "[\"MODEL_9\"]", 0), "models");
        cases.put(body("SETTING_2", "[\"MODEL_2\",\"MODEL_2\"]", 0), "models");
        cases.put(body("SETTING_3", "[\"MODEL_2\"]", 0), "setting");
        cases.put("{\"models\":[\"MODEL_2\"],\"expectedVersion\":0}", "setting");
        cases.put("{\"setting\":\"SETTING_2\",\"expectedVersion\":0}", "models");
        cases.put("{\"setting\":\"SETTING_2\",\"models\":[\"MODEL_2\"]}", "expectedVersion");
        for (var c : cases.entrySet()) {
            save(admin, c.getKey()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value(c.getValue()));
        }
        assertThat(versions()).isZero();
        assertThat(audit()).hasSize(cases.size()).allMatch(a -> a.equals("MONETIZATION_CONFIG_CHANGE_REJECTED/FAILURE/invalid"));
    }

    @Test
    void ac4TheDatabaseRefusesAnInvalidCombinationEvenWithoutTheApi() {
        User admin = data.admin("db-check");
        String insert = "insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, "
                + "effective_from, created_at, created_by_user_id) values (?, ?, ?, ?, ?, now(), now(), ?)";
        assertThatThrownBy(() -> jdbc.update(insert, 1, "SETTING_1", false, true, false, admin.getId()))
                .hasMessageContaining("ck_monetization_config_setting");
        assertThatThrownBy(() -> jdbc.update(insert, 1, "SETTING_2", false, false, false, admin.getId()))
                .hasMessageContaining("ck_monetization_config_setting");
        assertThatThrownBy(() -> jdbc.update(insert, 1, "BOTH", true, false, false, admin.getId()))
                .hasMessageContaining("ck_monetization_config_setting");
        assertThat(versions()).isZero();
    }

    @Test
    void ac5OnlySuperAdminsCanReadOrChangeTheConfiguration() throws Exception {
        Tenant owner = data.tenant("not-admin");
        User adminUser = data.admin("matrix");
        String admin = data.adminBearer(adminUser);
        String valid = body("SETTING_2", "[\"MODEL_2\"]", 0);
        for (String path : List.of(PATH, PATH + "/history")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", data.bearer(owner))).andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", admin)).andExpect(status().isOk());
        }
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnauthorized());
        save(data.bearer(owner), valid).andExpect(status().isForbidden());
        assertThat(versions()).isZero();

        adminUser.setRole(UserRole.VENUE); // the database role decides on every request
        users.save(adminUser);
        save(admin, valid).andExpect(status().isForbidden());
        assertThat(versions()).isZero();
    }

    @Test
    void ac6ChangesAreAuditedWithActorOldAndNewAndNoSecrets() throws Exception {
        User adminUser = data.admin("auditor");
        String admin = data.adminBearer(adminUser);
        save(admin, body("SETTING_1", "[]", 0)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_4\",\"MODEL_2\"]", 1)).andExpect(status().isOk());

        assertThat(audit()).containsExactly(
                "MONETIZATION_CONFIG_CHANGED/SUCCESS/SETTING_1 [MODEL_1] (was not configured)",
                "MONETIZATION_CONFIG_CHANGED/SUCCESS/SETTING_2 [MODEL_2, MODEL_4] (was SETTING_1 [MODEL_1])");
        List<Map<String, Object>> rows = jdbc.queryForList("select * from admin_audit_event where action like 'MONETIZATION%' order by occurred_at");
        assertThat(rows.get(1)).containsEntry("actor_user_id", adminUser.getId())
                .containsEntry("target_type", "MONETIZATION_CONFIG").containsEntry("target_id", "2");
        for (Map<String, Object> row : rows) {
            assertThat((String) row.get("actor_label")).contains("***").doesNotContain(adminUser.getEmail());
            for (Object value : row.values()) assertThat(String.valueOf(value)).doesNotContain(admin.substring(7));
        }
    }

    @Test
    void ac7VersionsAreImmutableAndTheHistoryIsPagedNewestFirst() throws Exception {
        String admin = data.adminBearer(data.admin("history"));
        save(admin, body("SETTING_1", "[]", 0)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_3\"]", 1)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_2\",\"MODEL_3\"]", 2)).andExpect(status().isOk());

        mockMvc.perform(get(PATH + "/history").param("size", "2").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[1].version").value(2))
                .andExpect(jsonPath("$.items[1].models[0]").value("MODEL_3"));
        mockMvc.perform(get(PATH + "/history").param("size", "101").header("Authorization", admin)).andExpect(status().isBadRequest());

        Map<String, Object> before = jdbc.queryForMap("select * from monetization_config where version = 1");
        assertThatThrownBy(() -> jdbc.update("update monetization_config set setting = 'SETTING_2', model_2_enabled = true where version = 1"))
                .hasMessageContaining("monetization_config is append-only");
        assertThatThrownBy(() -> jdbc.update("delete from monetization_config"))
                .hasMessageContaining("monetization_config is append-only");
        assertThat(jdbc.queryForMap("select * from monetization_config where version = 1")).isEqualTo(before);
        assertThat(versions()).isEqualTo(3);
    }

    @Test
    void ac8AStaleExpectedVersionIsAConflictAndIsAudited() throws Exception {
        String admin = data.adminBearer(data.admin("stale"));
        save(admin, body("SETTING_1", "[]", 0)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_2\"]", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        save(admin, body("SETTING_2", "[\"MODEL_2\"]", 5)).andExpect(status().isConflict());
        assertThat(versions()).isEqualTo(1);
        assertThat(audit()).endsWith("MONETIZATION_CONFIG_CHANGE_REJECTED/FAILURE/version_conflict",
                "MONETIZATION_CONFIG_CHANGE_REJECTED/FAILURE/version_conflict");
    }

    @Test
    void ac9AChangeTouchesNoRestaurantDataAndIsNeverBackdated() throws Exception {
        data.tenant("grandfathered-a");
        data.tenant("grandfathered-b");
        String admin = data.adminBearer(data.admin("fingerprint"));
        Map<String, String> before = fingerprint();
        Instant start = Instant.now().minusSeconds(1);

        save(admin, body("SETTING_1", "[]", 0)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_2\",\"MODEL_3\",\"MODEL_4\"]", 1)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_4\"]", 2)).andExpect(status().isOk());

        assertThat(fingerprint()).isEqualTo(before);
        for (Map<String, Object> row : jdbc.queryForList("select effective_from, created_at from monetization_config")) {
            Instant effective = ((Timestamp) row.get("effective_from")).toInstant();
            assertThat(effective).isAfterOrEqualTo(start).isEqualTo(((Timestamp) row.get("created_at")).toInstant());
        }
    }

    @Test
    void noOpSavingTheCurrentConfigurationCreatesNoVersionAndNoAudit() throws Exception {
        String admin = data.adminBearer(data.admin("no-op"));
        save(admin, body("SETTING_2", "[\"MODEL_2\",\"MODEL_4\"]", 0)).andExpect(status().isOk());
        save(admin, body("SETTING_2", "[\"MODEL_4\",\"MODEL_2\"]", 1)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(versions()).isEqualTo(1);
        assertThat(audit()).hasSize(1);
    }

    /** Every table except the two E4 admin tables, as a content hash (restaurant, owner, menu, special, views, …). */
    private Map<String, String> fingerprint() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' "
                + "and table_type = 'BASE TABLE' and table_name not in ('monetization_config', 'admin_audit_event') order by 1", String.class)) {
            result.put(table, jdbc.queryForObject("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from \""
                    + table + "\" t", String.class));
        }
        assertThat(result).containsKeys("business", "users", "menu");
        return result;
    }
}
