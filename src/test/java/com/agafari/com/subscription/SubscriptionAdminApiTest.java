package com.agafari.com.subscription;

import com.agafari.com.enums.UserRole;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M3 (AC5–AC11): the Super Admin records manual subscriptions (no payment verified) for plans the current
 * configuration enables, ends them, and sees each restaurant's history. One current subscription per restaurant; ended
 * ones are kept; no plan change; configuration changes never touch subscriptions; owners and guests are unaffected.
 */
class SubscriptionAdminApiTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;

    private String admin;

    private String admin() {
        if (admin == null) admin = data.adminBearer(data.admin("subs-admin"));
        return admin;
    }

    private int configure(String setting, String models) throws Exception {
        Integer current = jdbc.queryForObject("select coalesce(max(version), 0) from monetization_config", Integer.class);
        mockMvc.perform(put("/api/admin/monetization-config").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setting\":\"" + setting + "\",\"models\":" + models + ",\"expectedVersion\":" + current + "}")).andExpect(status().isOk());
        return jdbc.queryForObject("select max(version) from monetization_config", Integer.class);
    }

    private ResultActions record(String businessId, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/businesses/{id}/subscription", businessId).header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions change(String businessId, String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", businessId).header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String create(String plan, int configVersion) {
        return "{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":" + configVersion + "}";
    }

    private int rows(String businessId) {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, businessId);
    }

    private List<String> audit() {
        return jdbc.queryForList("select action || '/' || outcome || '/' || coalesce(detail, '') from admin_audit_event "
                + "where action like 'SUBSCRIPTION%' order by occurred_at", String.class);
    }

    @Test
    void plansListTheFourModelsAndWhichTheCurrentConfigurationEnables() throws Exception {
        configure("SETTING_2", "[\"MODEL_2\",\"MODEL_4\"]");
        mockMvc.perform(get("/api/admin/plans").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("MODEL_1")).andExpect(jsonPath("$[0].enabledByCurrentConfiguration").value(false))
                .andExpect(jsonPath("$[1].adSource").value("AGAFARI")).andExpect(jsonPath("$[1].enabledByCurrentConfiguration").value(true))
                .andExpect(jsonPath("$[2].adSource").value("RESTAURANT")).andExpect(jsonPath("$[2].enabledByCurrentConfiguration").value(false))
                .andExpect(jsonPath("$[3].adSource").value("NONE")).andExpect(jsonPath("$[3].paid").value(true));
    }

    @Test
    void theRestaurantListShowsBusinessDataAndSubscriptionStatusButNoOwnerEmail() throws Exception {
        Tenant cafe = data.tenant("list-cafe");
        Tenant bar = data.tenant("list-bar");
        int v = configure("SETTING_2", "[\"MODEL_4\"]");
        record(bar.businessId(), create("MODEL_4", v)).andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/admin/businesses").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(cafe.owner().getEmail()).doesNotContain(bar.owner().getEmail()).doesNotContain("passwordHash");
        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        Map<String, Object> barItem = items.stream().filter(i -> i.get("id").equals(bar.businessId())).findFirst().orElseThrow();
        assertThat(barItem).containsEntry("subscriptionStatus", "ACTIVE").containsEntry("planCode", "MODEL_4")
                .containsEntry("slug", bar.business().getCustomSubdomain());
        Map<String, Object> cafeItem = items.stream().filter(i -> i.get("id").equals(cafe.businessId())).findFirst().orElseThrow();
        assertThat(cafeItem.get("subscriptionStatus")).isNull();

        mockMvc.perform(get("/api/admin/businesses").param("query", "list-ba").header("Authorization", admin()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(bar.businessId()));
        mockMvc.perform(get("/api/admin/businesses").param("size", "101").header("Authorization", admin())).andExpect(status().isBadRequest());
    }

    @Test
    void ac5AManualRecordIsActiveStoresTheCurrentConfigurationVersionAndSaysNoPaymentWasVerified() throws Exception {
        Tenant t = data.tenant("manual");
        int v = configure("SETTING_2", "[\"MODEL_2\",\"MODEL_3\"]");
        String periodEnd = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        record(t.businessId(), "{\"planCode\":\"MODEL_3\",\"periodEnd\":\"" + periodEnd + "\",\"expectedConfigVersion\":" + v
                + ",\"source\":\"PROVIDER\",\"status\":\"TRIAL\"}") // source and status can't be chosen by the request
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.planCode").value("MODEL_3"))
                .andExpect(jsonPath("$.configVersion").value(v))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.paymentVerified").value(false))
                .andExpect(jsonPath("$.sourceLabel").value("Manual record, no payment verified"))
                .andExpect(jsonPath("$.periodEnd").value(periodEnd))
                .andExpect(jsonPath("$.rowVersion").isNumber());
        assertThat(jdbc.queryForMap("select status, source, config_version from business_subscription where business_id = ?", t.businessId()))
                .containsEntry("status", "ACTIVE").containsEntry("source", "MANUAL").containsEntry("config_version", v);
        assertThat(audit()).singleElement().asString().startsWith("SUBSCRIPTION_CREATED/SUCCESS/").contains("MODEL_3").contains("source=MANUAL")
                .contains("config=" + v);
    }

    @Test
    void ac5Setting1AllowsOnlyModel1AndSetting2OnlyItsEnabledModels() throws Exception {
        Tenant a = data.tenant("set1");
        int v1 = configure("SETTING_1", "[]");
        record(a.businessId(), create("MODEL_2", v1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("planCode"));
        record(a.businessId(), create("MODEL_1", v1)).andExpect(status().isCreated());

        Tenant b = data.tenant("set2");
        int v2 = configure("SETTING_2", "[\"MODEL_4\"]");
        for (String plan : List.of("MODEL_1", "MODEL_2", "MODEL_3", "MODEL_9")) {
            record(b.businessId(), create(plan, v2)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("planCode"));
        }
        record(b.businessId(), create("MODEL_4", v2)).andExpect(status().isCreated());
    }

    @Test
    void ac6InvalidRequestsCreateNothingAndAreAudited() throws Exception {
        Tenant t = data.tenant("invalid-sub");
        record(t.businessId(), create("MODEL_1", 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MONETIZATION_NOT_CONFIGURED"));
        int v = configure("SETTING_2", "[\"MODEL_2\"]");
        record(t.businessId(), create("MODEL_2", v - 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        record(t.businessId(), "{\"planCode\":\"MODEL_2\",\"periodEnd\":\"2020-01-01T00:00:00Z\",\"expectedConfigVersion\":" + v + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("periodEnd"));
        record(t.businessId(), "{\"planCode\":\"MODEL_2\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expectedConfigVersion"));
        record("no-such-restaurant", create("MODEL_2", v)).andExpect(status().isNotFound());

        assertThat(rows(t.businessId())).isZero();
        assertThat(audit()).hasSize(5).allMatch(a -> a.startsWith("SUBSCRIPTION_CHANGE_REJECTED/FAILURE/"));
    }

    @Test
    void ac7ASecondCurrentSubscriptionIsRefused() throws Exception {
        Tenant t = data.tenant("one-current");
        int v = configure("SETTING_2", "[\"MODEL_2\",\"MODEL_4\"]");
        record(t.businessId(), create("MODEL_2", v)).andExpect(status().isCreated());
        record(t.businessId(), create("MODEL_4", v)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        assertThat(rows(t.businessId())).isEqualTo(1);
    }

    @Test
    void ac7aEndingKeepsTheRowAndANewSubscriptionIsANewRowWithItsOwnConfigurationVersion() throws Exception {
        Tenant t = data.tenant("history");
        int v1 = configure("SETTING_2", "[\"MODEL_2\"]");
        String created = record(t.businessId(), create("MODEL_2", v1)).andReturn().getResponse().getContentAsString();
        int rowVersion = JsonPath.read(created, "$.rowVersion");

        change(t.businessId(), "{\"status\":\"EXPIRED\",\"rowVersion\":" + rowVersion + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.endedAt").isNotEmpty());
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current").doesNotExist());

        int v2 = configure("SETTING_1", "[]");
        record(t.businessId(), create("MODEL_1", v2)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/businesses/{id}/subscriptions", t.businessId()).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].planCode").value("MODEL_1")).andExpect(jsonPath("$[0].configVersion").value(v2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].planCode").value("MODEL_2")).andExpect(jsonPath("$[1].configVersion").value(v1))
                .andExpect(jsonPath("$[1].status").value("EXPIRED"));
    }

    @Test
    void ac7bNoPlanChangeAndNoOtherStatusThroughTheApi() throws Exception {
        Tenant t = data.tenant("no-plan-change");
        int v = configure("SETTING_2", "[\"MODEL_2\",\"MODEL_4\"]");
        String created = record(t.businessId(), create("MODEL_2", v)).andReturn().getResponse().getContentAsString();
        int rowVersion = JsonPath.read(created, "$.rowVersion");

        change(t.businessId(), "{\"planCode\":\"MODEL_4\",\"rowVersion\":" + rowVersion + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("planCode"));
        for (String status : List.of("ACTIVE", "TRIAL", "GRANDFATHERED", "PAUSED")) {
            change(t.businessId(), "{\"status\":\"" + status + "\",\"rowVersion\":" + rowVersion + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        }
        assertThat(jdbc.queryForMap("select plan_code, status from business_subscription where business_id = ?", t.businessId()))
                .containsEntry("plan_code", "MODEL_2").containsEntry("status", "ACTIVE");
    }

    @Test
    void periodEndCanBeAdjustedWithTheCurrentRowVersionOnly() throws Exception {
        Tenant t = data.tenant("period");
        int v = configure("SETTING_2", "[\"MODEL_4\"]");
        String created = record(t.businessId(), create("MODEL_4", v)).andReturn().getResponse().getContentAsString();
        int rowVersion = JsonPath.read(created, "$.rowVersion");
        String periodEnd = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

        change(t.businessId(), "{\"periodEnd\":\"" + periodEnd + "\",\"rowVersion\":" + rowVersion + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.periodEnd").value(periodEnd));
        change(t.businessId(), "{\"status\":\"EXPIRED\",\"rowVersion\":" + rowVersion + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_VERSION_CONFLICT"));
        change(t.businessId(), "{\"periodEnd\":\"2020-01-01T00:00:00Z\",\"rowVersion\":" + (rowVersion + 1) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("periodEnd"));
        assertThat(audit()).anyMatch(a -> a.startsWith("SUBSCRIPTION_UPDATED/SUCCESS/"))
                .anyMatch(a -> a.equals("SUBSCRIPTION_CHANGE_REJECTED/FAILURE/version_conflict"));

        Tenant none = data.tenant("no-current");
        change(none.businessId(), "{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isNotFound());
    }

    @Test
    void ac8AConfigurationChangeNeverTouchesSubscriptionsAndFlagsThoseOutsideIt() throws Exception {
        Tenant t = data.tenant("outside");
        int v = configure("SETTING_2", "[\"MODEL_4\"]");
        record(t.businessId(), create("MODEL_4", v)).andExpect(status().isCreated());
        Map<String, Object> before = jdbc.queryForMap("select * from business_subscription where business_id = ?", t.businessId());

        configure("SETTING_1", "[]");

        assertThat(jdbc.queryForMap("select * from business_subscription where business_id = ?", t.businessId())).isEqualTo(before);
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", admin()))
                .andExpect(jsonPath("$.current.outsideCurrentConfiguration").value(true))
                .andExpect(jsonPath("$.current.configVersion").value(v));
    }

    @Test
    void ac9OnlySuperAdminsCanUseThePlanRestaurantAndSubscriptionApis() throws Exception {
        Tenant owner = data.tenant("not-admin-sub");
        User adminUser = data.admin("demotable");
        String adminToken = data.adminBearer(adminUser);
        List<String> reads = List.of("/api/admin/plans", "/api/admin/businesses",
                "/api/admin/businesses/" + owner.businessId() + "/subscription", "/api/admin/businesses/" + owner.businessId() + "/subscriptions");
        for (String path : reads) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", data.bearer(owner))).andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", adminToken)).andExpect(status().isOk());
        }
        String body = create("MODEL_1", 1);
        mockMvc.perform(post("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EXPIRED\",\"rowVersion\":0}")).andExpect(status().isForbidden());

        adminUser.setRole(UserRole.VENUE);
        users.save(adminUser);
        mockMvc.perform(get("/api/admin/plans").header("Authorization", adminToken)).andExpect(status().isForbidden());
    }

    @Test
    void ac10AuditRowsHoldNoOwnerEmailOrTokens() throws Exception {
        Tenant t = data.tenant("audit-sub");
        int v = configure("SETTING_2", "[\"MODEL_2\"]");
        String created = record(t.businessId(), create("MODEL_2", v)).andReturn().getResponse().getContentAsString();
        change(t.businessId(), "{\"status\":\"EXPIRED\",\"rowVersion\":" + JsonPath.read(created, "$.rowVersion") + "}").andExpect(status().isOk());

        assertThat(audit()).hasSize(2);
        assertThat(audit().get(1)).startsWith("SUBSCRIPTION_UPDATED/SUCCESS/").contains("ACTIVE -> EXPIRED");
        for (Map<String, Object> row : jdbc.queryForList("select * from admin_audit_event where action like 'SUBSCRIPTION%'")) {
            assertThat(row).containsEntry("target_type", "BUSINESS_SUBSCRIPTION");
            for (Object value : row.values()) {
                assertThat(String.valueOf(value)).doesNotContain(t.owner().getEmail()).doesNotContain(admin().substring(7));
            }
        }
    }

    @Test
    void ac10OwnersAndGuestsAreUnaffectedBySubscriptionChanges() throws Exception {
        Tenant t = data.tenant("unaffected");
        admin(); // the admin account exists before the snapshot
        Map<String, String> before = fingerprint();
        int v = configure("SETTING_2", "[\"MODEL_2\"]");
        String created = record(t.businessId(), create("MODEL_2", v)).andReturn().getResponse().getContentAsString();
        change(t.businessId(), "{\"status\":\"EXPIRED\",\"rowVersion\":" + JsonPath.read(created, "$.rowVersion") + "}").andExpect(status().isOk());

        assertThat(fingerprint()).isEqualTo(before);
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(t))).andExpect(status().isOk());
        mockMvc.perform(get("/api/specials").header("Authorization", data.bearer(t))).andExpect(status().isOk());
        mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andExpect(status().isOk());
    }

    /** Every table except the E4 admin tables, as a content hash. */
    private Map<String, String> fingerprint() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' "
                + "and table_name not in ('monetization_config', 'admin_audit_event', 'business_subscription') order by 1", String.class)) {
            result.put(table, jdbc.queryForObject("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from \"" + table + "\" t", String.class));
        }
        assertThat(result).containsKeys("business", "users", "menu", "subscription_plan");
        return result;
    }
}
