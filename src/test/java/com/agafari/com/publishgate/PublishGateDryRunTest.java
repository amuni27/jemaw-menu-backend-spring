package com.agafari.com.publishgate;

import com.agafari.com.billing.Market;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.BillingFlags.MarketFlags;
import com.agafari.com.config.FlagMatrixValidator;
import com.agafari.com.config.FlagMatrixViolationException;
import com.agafari.com.config.PublishGateReadiness;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.PublishEligibility;
import com.agafari.com.service.RestaurantCutOver;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF BE-6 (register §56): the read-only publishing-gate dry run per market, its sign-off, and the flag-matrix facts it
 * feeds. Restaurants are classified by the same rule as the gate (BE-4) "as if the gate were on": existing (created before
 * the cut-over, B-1), new and allowed, new and blocked; plus the published menus of restaurants without an entitled plan,
 * items for manual review, and restaurants outside US/ET. A sign-off is an audit event per market, bound to the cut-over and
 * valid 14 days (D-3); the gate then also needs owner plans visible and a configuration (D-5). Nothing here enables the gate.
 */
class PublishGateDryRunTest extends AbstractIntegrationTest {

    private static final Instant CUT_OVER = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RestaurantCutOver cutOver;
    @Autowired private PlanRequestService planRequests;
    @Autowired private PublishEligibility gate;
    @Autowired private PublishGateReadiness readiness;
    @Autowired private FlagMatrixValidator validator;
    @Autowired private ObjectMapper mapper;
    @Autowired private com.agafari.com.service.MonetizationClock clock;

    private User admin;
    private final Map<String, Tenant> t = new HashMap<>();

    @BeforeEach
    void setUp(TestInfo test) {
        admin = data.admin("dr-admin");
        if (!test.getTestMethod().orElseThrow().getName().equals("withoutAConfigurationTheReportIsBlocked"))
            config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        cutOver.setFrom(CUT_OVER);
        planRequests.setOwnerPlansVisible(true);
    }

    @AfterEach
    void reset() {
        cutOver.setFrom(null);
        planRequests.setOwnerPlansVisible(false);
        gate.setGate(Market.ET, false);
        gate.setGate(Market.US, false);
        clock.reset();
    }

    // ---- fixtures ----

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private Tenant existing(String label) {
        Tenant x = data.tenant(label);
        jdbc.update("update business set \"createdAt\" = ? where id = ?", Timestamp.from(Instant.parse("2025-06-01T00:00:00Z")), x.businessId());
        t.put(label, x);
        return x;
    }

    private Tenant fresh(String label) {
        Tenant x = data.tenant(label);
        t.put(label, x);
        return x;
    }

    private void trial(Tenant x, Instant periodEnd) {
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                        + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                UUID.randomUUID().toString(), x.businessId(), Timestamp.from(periodEnd), Timestamp.from(periodEnd.minus(Duration.ofDays(30))),
                Timestamp.from(periodEnd.minus(Duration.ofDays(30))));
    }

    /** The scenario matrix of R1. */
    private void matrix() {
        Tenant e1 = existing("dr-e1-published");
        data.item(data.category(data.menu(e1, "E1 menu"), "Mains"), "Tibs");
        existing("dr-e2-draft");
        Tenant e3 = existing("dr-e3-expired");
        trial(e3, days(-10));
        data.menu(e3, "E3 menu");

        fresh("dr-n1-none");
        data.menu(t.get("dr-n1-none"), "N1 private", true, MenuVisibility.PRIVATE);
        trial(fresh("dr-n2-trial"), days(10));
        Tenant n3 = fresh("dr-n3-trial-ended");
        trial(n3, days(-1));
        data.menu(n3, "N3 menu");
        Tenant n4 = fresh("dr-n4-manual");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                        + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_2', 'ACTIVE', 1, ?, 'MANUAL', ?, ?, ?, ?, 0)",
                UUID.randomUUID().toString(), n4.businessId(), Timestamp.from(days(-3)), Timestamp.from(days(-40)), admin.getId(), Timestamp.from(days(-40)), admin.getId());
        data.menu(n4, "N4 menu");
        Tenant n5 = fresh("dr-n5-requested");
        pendingRequest(jdbc, n5.businessId(), "MODEL_4", 1, n5.owner().getId());
        Tenant us = fresh("dr-n6-us");
        jdbc.update("update business set country = 'US' where id = ?", us.businessId());
        Tenant ke = fresh("dr-n7-ke");
        jdbc.update("update business set country = 'KE' where id = ?", ke.businessId());

        jdbc.update("update users set status = 'INACTIVE' where id = ?", t.get("dr-e2-draft").owner().getId());
        jdbc.update("insert into trial_intent (business_id, config_version, status, failed_attempts, created_at, row_version) "
                + "values (?, 1, 'PENDING', 0, clock_timestamp(), 0)", e1.businessId());
    }

    // ---- HTTP ----

    private MockHttpServletResponse call(String bearer, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer)).andReturn().getResponse();
    }

    private JsonNode summary(String market) throws Exception {
        MockHttpServletResponse r = call(data.adminBearer(admin), "/api/admin/publish-gate/dry-run?market=" + market);
        assertThat(r.getStatus()).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    private List<JsonNode> section(String market, String section) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            MockHttpServletResponse r = call(data.adminBearer(admin), "/api/admin/publish-gate/dry-run/sections/" + section + "?market=" + market + "&page=" + page + "&size=2");
            assertThat(r.getStatus()).isEqualTo(200);
            JsonNode body = mapper.readTree(r.getContentAsString());
            body.path("items").forEach(all::add);
            if ((page + 1) * 2 >= body.path("total").asInt()) return all;
        }
    }

    private List<String> names(List<JsonNode> rows) {
        return rows.stream().map(r -> r.path("name").asString()).sorted().toList();
    }

    private String reasonOf(List<JsonNode> rows, String label) {
        return rows.stream().filter(r -> r.path("businessId").asString().equals(t.get(label).businessId())).findFirst().orElseThrow().path("reason").asString();
    }

    private MockHttpServletResponse signOff(String bearer, String market, String fingerprint) throws Exception {
        return mockMvc.perform(post("/api/admin/publish-gate/dry-run/sign-off").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"market\":\"" + market + "\",\"fingerprint\":\"" + fingerprint + "\"}")).andReturn().getResponse();
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> m = new HashMap<>();
        for (String table : List.of("business", "menu", "business_subscription", "plan_request", "trial_intent", "users", "admin_audit_event")) {
            m.put(table, jdbc.queryForObject("select md5(coalesce(string_agg(x::text, '|' order by x::text), '')) from " + table + " x", String.class));
        }
        return m;
    }

    // ---- R1: sections ----

    @Test
    void everyRestaurantLandsInTheSectionTheGateWouldGiveIt() throws Exception {
        matrix();

        assertThat(names(section("ET", "EXEMPT"))).containsExactly("dr-e1-published Restaurant", "dr-e2-draft Restaurant", "dr-e3-expired Restaurant");
        List<JsonNode> allowed = section("ET", "ALLOWED");
        assertThat(names(allowed)).containsExactly("dr-n2-trial Restaurant", "dr-n4-manual Restaurant");
        assertThat(reasonOf(allowed, "dr-n2-trial")).isEqualTo("TRIAL");
        assertThat(reasonOf(allowed, "dr-n4-manual")).isEqualTo("ACTIVE_MANUAL");
        List<JsonNode> blocked = section("ET", "BLOCKED");
        assertThat(names(blocked)).containsExactly("dr-n1-none Restaurant", "dr-n3-trial-ended Restaurant", "dr-n5-requested Restaurant");
        assertThat(reasonOf(blocked, "dr-n1-none")).isEqualTo("NO_PLAN");
        assertThat(reasonOf(blocked, "dr-n3-trial-ended")).isEqualTo("TRIAL_ENDED");
        JsonNode requested = blocked.stream().filter(r -> r.path("name").asString().startsWith("dr-n5")).findFirst().orElseThrow();
        assertThat(requested.path("pendingRequest").asBoolean()).isTrue();
        assertThat(names(section("ET", "NOT_GATED"))).containsExactly("dr-n7-ke Restaurant");
        assertThat(names(section("US", "BLOCKED"))).containsExactly("dr-n6-us Restaurant");

        // the same rule as the gate itself, forced on
        gate.setGate(Market.ET, true);
        for (String section : List.of("EXEMPT", "ALLOWED", "BLOCKED")) {
            for (JsonNode row : section("ET", section)) {
                PublishEligibility.Decision d = gate.decide(row.path("businessId").asString());
                assertThat(d).as(row.path("name").asString()).isEqualTo(switch (section) {
                    case "EXEMPT" -> PublishEligibility.Decision.EXEMPT;
                    case "ALLOWED" -> PublishEligibility.Decision.ENTITLED;
                    default -> PublishEligibility.Decision.REFUSED;
                });
            }
        }
    }

    @Test
    void countsAddUpAndTheCsvMatches() throws Exception {
        matrix();
        JsonNode s = summary("ET");
        JsonNode c = s.path("counts");
        assertThat(c.path("exempt").asInt()).isEqualTo(3);
        assertThat(c.path("allowed").asInt()).isEqualTo(2);
        assertThat(c.path("blocked").asInt()).isEqualTo(3);
        assertThat(c.path("errors").asInt()).isZero();
        assertThat(c.path("total").asInt()).isEqualTo(8).isEqualTo(jdbc.queryForObject("select count(*) from business where country = 'ET'", Integer.class));
        assertThat(c.path("notGated").asInt()).isEqualTo(1);

        MockHttpServletResponse csv = call(data.adminBearer(admin), "/api/admin/publish-gate/dry-run.csv?market=ET");
        assertThat(csv.getStatus()).isEqualTo(200);
        assertThat(csv.getContentType()).startsWith("text/csv");
        List<String> lines = csv.getContentAsString().lines().toList();
        assertThat(lines.get(0)).isEqualTo("section,businessId,name,country,createdAt,ownerStatus,emailVerified,publishedMenus,plan,reason,menuId,menuName,flags");
        int rows = c.path("exempt").asInt() + c.path("allowed").asInt() + c.path("blocked").asInt() + c.path("publishedWithoutPlan").asInt()
                + c.path("review").asInt() + c.path("notGated").asInt();
        assertThat(lines).hasSize(rows + 1);
    }

    // ---- R3: published menus without a plan; read only ----

    @Test
    void publishedMenusOfRestaurantsWithoutAnEntitledPlanAreListedAndNothingChanges() throws Exception {
        matrix();
        Map<String, Object> before = snapshot();

        List<JsonNode> menus = section("ET", "PUBLISHED_WITHOUT_PLAN");
        assertThat(menus.stream().map(m -> m.path("menuName").asString()).sorted().toList()).containsExactly("E1 menu", "E3 menu", "N3 menu");
        assertThat(menus.stream().filter(m -> m.path("menuName").asString().equals("E1 menu")).findFirst().orElseThrow().path("restaurantSection").asString())
                .isEqualTo("EXEMPT");
        summary("ET");
        call(data.adminBearer(admin), "/api/admin/publish-gate/dry-run.csv?market=ET");

        assertThat(snapshot()).as("the dry run writes nothing").isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from menu where is_active and visibility = 'PUBLIC'", Integer.class)).isEqualTo(4);
    }

    // ---- R4: manual review ----

    @Test
    void itemsNeedingManualReviewAreFlagged() throws Exception {
        matrix();
        Map<String, List<String>> flags = new HashMap<>();
        for (JsonNode r : section("ET", "REVIEW")) {
            List<String> f = new ArrayList<>();
            r.path("flags").forEach(x -> f.add(x.asString()));
            flags.put(r.path("name").asString(), f);
        }
        assertThat(flags.get("dr-e2-draft Restaurant")).contains("OWNER_INACTIVE");
        assertThat(flags.get("dr-e1-published Restaurant")).contains("LEGACY_TRIAL_INTENT");
        assertThat(flags.get("dr-n4-manual Restaurant")).contains("ENTITLED_ONLY_BECAUSE_PERIOD_END_OFF");
        assertThat(flags.get("dr-n1-none Restaurant")).contains("ACTIVE_PRIVATE_MENU");
        assertThat(flags).doesNotContainKey("dr-n2-trial Restaurant");
    }

    // ---- R5: header and blockers ----

    @Test
    void theHeaderShowsTheFactsAndNoBlockersWhenReady() throws Exception {
        JsonNode s = summary("ET");
        assertThat(s.path("market").asString()).isEqualTo("ET");
        assertThat(s.path("cutOver").asString()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(s.path("ownerPlansVisible").asBoolean()).isTrue();
        assertThat(s.path("config").path("version").asInt()).isEqualTo(1);
        assertThat(s.path("config").path("setting").asString()).isEqualTo("SETTING_2");
        assertThat(s.path("gateEnabled").asBoolean(true)).isFalse();
        assertThat(s.path("periodEndEnabled").isBoolean()).isTrue();
        assertThat(s.path("blockers").isEmpty()).isTrue();
        assertThat(s.path("fingerprint").asString()).hasSize(64);
    }

    @Test
    void ownerPlansHiddenOrNoCutOverBlockTheSignOff() throws Exception {
        planRequests.setOwnerPlansVisible(false);
        JsonNode s = summary("ET");
        assertThat(s.path("blockers").toString()).contains("OWNER_PLANS_HIDDEN");
        MockHttpServletResponse r = signOff(data.adminBearer(admin), "ET", s.path("fingerprint").asString());
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentAsString()).contains("DRY_RUN_BLOCKED");

        planRequests.setOwnerPlansVisible(true);
        cutOver.setFrom(null);
        s = summary("ET");
        assertThat(s.path("blockers").toString()).contains("CUT_OVER_NOT_CONFIGURED");
        assertThat(signOff(data.adminBearer(admin), "ET", s.path("fingerprint").asString()).getStatus()).isEqualTo(409);
    }

    @Test
    void withoutAConfigurationTheReportIsBlocked() throws Exception {
        JsonNode s = summary("ET");
        assertThat(s.path("blockers").toString()).contains("MONETIZATION_NOT_CONFIGURED");
        assertThat(signOff(data.adminBearer(admin), "ET", s.path("fingerprint").asString()).getStatus()).isEqualTo(409);
    }

    // ---- R6: sign-off ----

    @Test
    void onlyASuperAdminCanSeeOrSignAndAStaleFingerprintIsRefused() throws Exception {
        matrix();
        Tenant owner = t.get("dr-n1-none");
        assertThat(call(data.bearer(owner), "/api/admin/publish-gate/dry-run?market=ET").getStatus()).isEqualTo(403);
        assertThat(signOff(data.bearer(owner), "ET", "x").getStatus()).isEqualTo(403);
        assertThat(call(data.adminBearer(admin), "/api/admin/publish-gate/dry-run?market=XX").getStatus()).isEqualTo(400);

        String seen = summary("ET").path("fingerprint").asString();
        fresh("dr-late-arrival"); // the report changes after it was viewed
        MockHttpServletResponse stale = signOff(data.adminBearer(admin), "ET", seen);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(stale.getContentAsString()).contains("DRY_RUN_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'PUBLISH_DRY_RUN_SIGNED_OFF'", Integer.class)).isZero();
    }

    @Test
    void aSignOffIsAuditedPerMarketAndTheLatestOneCounts() throws Exception {
        matrix();
        JsonNode s = summary("ET");
        MockHttpServletResponse r = signOff(data.adminBearer(admin), "ET", s.path("fingerprint").asString());
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(body.path("market").asString()).isEqualTo("ET");
        assertThat(body.path("cutOver").asString()).isEqualTo("2026-01-01T00:00:00Z");

        Map<String, Object> audit = jdbc.queryForMap("select * from admin_audit_event where action = 'PUBLISH_DRY_RUN_SIGNED_OFF'");
        assertThat(audit).containsEntry("actor_user_id", admin.getId()).containsEntry("target_type", "MARKET").containsEntry("target_id", "ET")
                .containsEntry("outcome", "SUCCESS");
        assertThat((String) audit.get("detail")).contains("cutOver=2026-01-01T00:00:00Z").contains("configVersion=1").contains("exempt=3")
                .contains("allowed=2").contains("blocked=3").contains("fingerprint=" + s.path("fingerprint").asString());

        JsonNode after = summary("ET").path("latestSignOff");
        assertThat(after.path("valid").asBoolean()).isTrue();
        assertThat(summary("US").path("latestSignOff").isMissingNode() || summary("US").path("latestSignOff").isNull()).isTrue();

        assertThat(signOff(data.adminBearer(admin), "ET", summary("ET").path("fingerprint").asString()).getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'PUBLISH_DRY_RUN_SIGNED_OFF'", Integer.class)).isEqualTo(2);
    }

    // ---- R7: the facts the flag matrix reads ----

    @Test
    void aSignOffCountsForItsMarketItsCutOverAndFourteenDays() throws Exception {
        assertThat(readiness.dryRunSignedOff(Market.ET)).isFalse();
        assertThat(signOff(data.adminBearer(admin), "ET", summary("ET").path("fingerprint").asString()).getStatus()).isEqualTo(201);
        assertThat(readiness.dryRunSignedOff(Market.ET)).isTrue();
        assertThat(readiness.dryRunSignedOff(Market.US)).as("per market").isFalse();

        cutOver.setFrom(CUT_OVER.plusSeconds(60));
        assertThat(readiness.dryRunSignedOff(Market.ET)).as("bound to the cut-over").isFalse();
        cutOver.setFrom(CUT_OVER);
        clock.setOffset(Duration.ofDays(13));
        assertThat(readiness.dryRunSignedOff(Market.ET)).as("still valid on day 13").isTrue();
        clock.setOffset(Duration.ofDays(15)); // the audit table is append-only: time moves instead
        assertThat(readiness.dryRunSignedOff(Market.ET)).as("valid 14 days").isFalse();
    }

    @Test
    void ownerPlansMustBeVisibleWithAConfiguration() {
        assertThat(readiness.ownerPlansReady()).isTrue();
        planRequests.setOwnerPlansVisible(false);
        assertThat(readiness.ownerPlansReady()).isFalse();
    }

    @Test
    void theValidatorAllowsTheGateOnlyWithTheseFacts() throws Exception {
        BillingFlags etGate = new BillingFlags(false, false, false, new MarketFlags(false, false, false, false), new MarketFlags(false, false, true, false));

        assertThatThrownBy(() -> validator.check(etGate, false)).isInstanceOf(FlagMatrixViolationException.class).hasMessageContaining("dry run");
        assertThat(signOff(data.adminBearer(admin), "ET", summary("ET").path("fingerprint").asString()).getStatus()).isEqualTo(201);
        assertThatCode(() -> validator.check(etGate, false)).as("D-2: no period-end needed while billing is off").doesNotThrowAnyException();

        planRequests.setOwnerPlansVisible(false);
        assertThatThrownBy(() -> validator.check(etGate, false)).isInstanceOf(FlagMatrixViolationException.class).hasMessageContaining("owner plans");
    }
}
