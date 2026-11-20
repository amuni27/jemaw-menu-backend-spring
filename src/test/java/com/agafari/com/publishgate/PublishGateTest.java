package com.agafari.com.publishgate;

import com.agafari.com.billing.Market;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.security.EmailVerificationService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.PublishEligibility;
import com.agafari.com.service.RestaurantCutOver;
import com.agafari.com.service.ScheduledChangeService;
import com.agafari.com.service.TrialService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF BE-4 (register §52): publishing a menu requires an entitled trial or subscription while
 * {@code app.billing.<market>.publish-requires-plan} is on for the restaurant's market (default off everywhere). Restaurants
 * created before the BE-5a cut-over are exempt (B-1). Only the step into "published" is gated, at both call sites (create
 * and patch); drafts, edits of published menus, categories, items, unpublishing and duplicating never are, and nothing is
 * ever unpublished (policy 7). Email verification is checked first (B-8). Pending requests, open checkouts and payments
 * under review grant nothing (policy 6; the latter two in {@code FinancialExceptionTest}). {@code publishAllowed} (BE-5b)
 * mirrors the decision.
 */
class PublishGateTest extends AbstractIntegrationTest {

    private static final String REFUSED = "PLAN_REQUIRED_TO_PUBLISH";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PublishEligibility gate;
    @Autowired private RestaurantCutOver cutOver;
    @Autowired private PlanRequestService planRequests;
    @Autowired private TrialService trials;
    @Autowired private EmailVerificationService verification;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private ScheduledChangeService scheduledChanges;
    @Autowired private MonetizationClock clock;
    @Autowired private com.agafari.com.config.BillingFlags flags;
    @Autowired private ObjectMapper mapper;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("pg-admin");
        owner = data.tenant("pg-owner");
        verify(owner);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        cutOver.setFrom(Instant.EPOCH); // every restaurant here is new unless a test says otherwise
        gate.setGate(Market.ET, true);
    }

    @AfterEach
    void reset() {
        gate.setGate(Market.ET, false);
        gate.setGate(Market.US, false);
        cutOver.setFrom(null);
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
        trials.setEnabled(false);
        verification.setEnforcePublishGate(false);
        evaluator.setPeriodEndEnabled(false);
        grandfathering.setEnabled(false);
        scheduledChanges.setBillingEnabled(false);
        clock.reset();
    }

    // ---- helpers ----

    private void verify(Tenant t) {
        jdbc.update("update users set email_verified_at = now() where id = ?", t.owner().getId());
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private MockHttpServletResponse create(Tenant t, boolean active, String visibility) throws Exception {
        String type = data.menuType("Lunch").getId();
        return mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lunch " + UUID.randomUUID().toString().substring(0, 6) + "\",\"menuTypeId\":\"" + type + "\",\"isActive\":" + active
                        + (visibility == null ? "" : ",\"visibility\":\"" + visibility + "\"") + "}")).andReturn().getResponse();
    }

    private MockHttpServletResponse publishNew(Tenant t) throws Exception {
        return create(t, true, null);
    }

    private MockHttpServletResponse patchMenu(Tenant t, String menuId, String json) throws Exception {
        return mockMvc.perform(patch("/api/menus/" + menuId).header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
    }

    private String draft(Tenant t) throws Exception {
        MockHttpServletResponse r = create(t, false, null);
        assertThat(r.getStatus()).isEqualTo(201);
        return mapper.readTree(r.getContentAsString()).path("id").asString();
    }

    private String code(MockHttpServletResponse r) throws Exception {
        return mapper.readTree(r.getContentAsString()).path("code").asString();
    }

    private void refused(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo(REFUSED);
        assertThat(mapper.readTree(r.getContentAsString()).path("message").asString())
                .isEqualTo("Choose a plan to publish your menu. You can keep editing it meanwhile.");
    }

    private int menus(Tenant t) {
        return jdbc.queryForObject("select count(*) from menu where business_id = ?", Integer.class, t.businessId());
    }

    private boolean active(String menuId) {
        return jdbc.queryForObject("select is_active from menu where id = ?", Boolean.class, menuId);
    }

    private void insertSubscription(Tenant t, String source, String status, String plan, Instant periodEnd, Integer graceDays) {
        if ("PROVIDER".equals(source)) jdbc.execute("alter table business_subscription disable trigger provider_row_has_evidence"); // reset per test
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, grace_days, ended_at, created_at, "
                        + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID().toString(), t.businessId(), plan, status, periodEnd == null ? null : Timestamp.from(periodEnd), source, graceDays,
                "EXPIRED".equals(status) ? Timestamp.from(periodEnd) : null, Timestamp.from(days(-40)), admin.getId(), Timestamp.from(days(-40)), admin.getId());
    }

    private void trial(Tenant t, Instant periodEnd, String status) {
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, ended_at, created_at, "
                        + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', ?, 1, ?, 'TRIAL', ?, ?, 'system', ?, 'system', 0)",
                UUID.randomUUID().toString(), t.businessId(), status, Timestamp.from(periodEnd), "EXPIRED".equals(status) ? Timestamp.from(periodEnd) : null,
                Timestamp.from(periodEnd.minus(Duration.ofDays(30))), Timestamp.from(periodEnd.minus(Duration.ofDays(30))));
    }

    private JsonNode planView(Tenant t) throws Exception {
        return mapper.readTree(mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString());
    }

    // ---- flag off ----

    @Test
    void withTheGateOffNothingChanges() throws Exception {
        assertThat(flags.et().publishRequiresPlan()).as("off by default").isFalse();
        assertThat(flags.us().publishRequiresPlan()).as("off by default").isFalse();
        gate.setGate(Market.ET, false);
        planRequests.setOwnerPlansVisible(true);

        assertThat(publishNew(owner).getStatus()).isEqualTo(201);
        String id = draft(owner);
        assertThat(patchMenu(owner, id, "{\"isActive\":true}").getStatus()).isEqualTo(200);
        assertThat(planView(owner).has("publishAllowed")).as("response unchanged while the gate is off").isFalse();
    }

    // ---- new restaurant without a plan ----

    @Test
    void aNewRestaurantWithoutAPlanCanPrepareButNotPublish() throws Exception {
        refused(publishNew(owner));
        assertThat(menus(owner)).as("a refused create writes nothing").isZero();

        String id = draft(owner);
        assertThat(patchMenu(owner, id, "{\"name\":\"Dinner\",\"description\":\"Evenings\"}").getStatus()).isEqualTo(200);
        refused(patchMenu(owner, id, "{\"isActive\":true}"));
        assertThat(active(id)).as("a refused patch changes nothing").isFalse();
        assertThat(jdbc.queryForObject("select name from menu where id = ?", String.class, id)).isEqualTo("Dinner");
    }

    @Test
    void makingAnActivePrivateMenuPublicIsAPublishToo() throws Exception {
        MockHttpServletResponse r = create(owner, true, "PRIVATE");
        assertThat(r.getStatus()).as("active but private is not published").isEqualTo(201);
        String id = mapper.readTree(r.getContentAsString()).path("id").asString();

        refused(patchMenu(owner, id, "{\"visibility\":\"PUBLIC\"}"));
        assertThat(jdbc.queryForObject("select visibility from menu where id = ?", String.class, id)).isEqualTo("PRIVATE");
    }

    @Test
    void draftContentIsNeverBlocked() throws Exception {
        String id = draft(owner);
        MockHttpServletResponse category = mockMvc.perform(post("/api/menus/" + id + "/categories").header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mains\"}")).andReturn().getResponse();
        assertThat(category.getStatus()).isEqualTo(201);
        String categoryId = mapper.readTree(category.getContentAsString()).path("id").asString();
        assertThat(mockMvc.perform(post("/api/menus/" + id + "/items").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":\"" + categoryId + "\",\"name\":\"Doro Wat\",\"price\":420,\"ingredients\":[\"chicken\"]}"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        assertThat(mockMvc.perform(post("/api/menus/" + id + "/duplicate").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn().getResponse().getStatus()).isBetween(200, 201);
    }

    // ---- entitled ----

    @Test
    void anActiveManualOrOwnerPlanAllowsPublishing() throws Exception {
        subscription(jdbc, owner.businessId(), "MODEL_2", 1, admin.getId());
        assertThat(publishNew(owner).getStatus()).isEqualTo(201);

        Tenant other = data.tenant("pg-owner-row");
        verify(other);
        insertSubscription(other, "OWNER", "ACTIVE", "MODEL_4", null, null);
        assertThat(publishNew(other).getStatus()).isEqualTo(201);
    }

    @Test
    void aRunningTrialAllowsPublishingAndStartingFreeWithAdsToo() throws Exception {
        trial(owner, days(10), "TRIAL");
        assertThat(publishNew(owner).getStatus()).isEqualTo(201);

        Tenant free = data.tenant("pg-free");
        verify(free);
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelfServiceEnabled(true);
        refused(publishNew(free));
        assertThat(mockMvc.perform(post("/api/plan/activations").header("Authorization", data.bearer(free)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}")).andReturn().getResponse().getStatus()).isEqualTo(201);
        assertThat(publishNew(free).getStatus()).isEqualTo(201);
    }

    @Test
    void aProviderRowAllowsPublishingWithinGraceButNotAfter() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        insertSubscription(owner, "PROVIDER", "ACTIVE", "MODEL_4", days(-1), 3);
        assertThat(publishNew(owner).getStatus()).as("PAST_DUE within grace (B-5)").isEqualTo(201);

        Tenant lapsed = data.tenant("pg-lapsed");
        verify(lapsed);
        insertSubscription(lapsed, "PROVIDER", "ACTIVE", "MODEL_4", days(-10), 3);
        refused(publishNew(lapsed));
    }

    @Test
    void aGrandfatheredPlanAllowsPublishingUntilItsApprovedEnd() throws Exception {
        insertSubscription(owner, "MANUAL", "ACTIVE", "MODEL_3", days(-5), null);
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId()); // Model 3 no longer offered: grandfathered
        assertThat(publishNew(owner).getStatus()).as("transitions off: still entitled").isEqualTo(201);

        grandfathering.setEnabled(true); // past its approved end, before the job
        refused(publishNew(owner));
    }

    // ---- not entitled ----

    @Test
    void anExpiredTrialGrantsNothingAlsoBeforeTheJobRuns() throws Exception {
        trial(owner, days(-1), "TRIAL"); // past its end, not yet persisted
        refused(publishNew(owner));

        Tenant stored = data.tenant("pg-expired");
        verify(stored);
        trial(stored, days(-5), "EXPIRED");
        refused(publishNew(stored));
    }

    @Test
    void anExpiredSubscriptionGrantsNothing() throws Exception {
        insertSubscription(owner, "MANUAL", "EXPIRED", "MODEL_4", days(-3), null);
        refused(publishNew(owner));
    }

    @Test
    void aPendingPlanRequestGrantsNothing() throws Exception {
        pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        refused(publishNew(owner));
    }

    @Test
    void aCancellationPastItsTimeGrantsNothingAlsoBeforeTheJobRuns() throws Exception {
        scheduledChanges.setBillingEnabled(true);
        insertSubscription(owner, "PROVIDER", "ACTIVE", "MODEL_4", days(5), null);
        scheduledChanges.scheduleCancel(owner.businessId(), owner.owner().getId(), "o@example.test");
        assertThat(publishNew(owner).getStatus()).as("before its time the plan still holds").isEqualTo(201);

        clock.setOffset(Duration.ofDays(6));
        refused(publishNew(owner));
    }

    // ---- existing restaurants (B-1) ----

    @Test
    void anExistingRestaurantKeepsPublishingWhateverItsState() throws Exception {
        Instant created = jdbc.queryForObject("select \"createdAt\" from business where id = ?", Timestamp.class, owner.businessId()).toInstant();
        cutOver.setFrom(created.plusNanos(1000));
        assertThat(publishNew(owner).getStatus()).isEqualTo(201);

        trial(owner, days(-5), "EXPIRED");
        assertThat(publishNew(owner).getStatus()).isEqualTo(201);
    }

    @Test
    void aRestaurantCreatedExactlyAtTheCutOverIsNew() throws Exception {
        cutOver.setFrom(jdbc.queryForObject("select \"createdAt\" from business where id = ?", Timestamp.class, owner.businessId()).toInstant());
        refused(publishNew(owner));
    }

    // ---- published menus (policy 7, B-6) ----

    @Test
    void aPublishedMenuStaysPublishedAndEditableAndCanBeUnpublished() throws Exception {
        Menu live = data.menu(owner, "Live"); // published before the gate (or before the plan ended)
        data.item(data.category(live, "Mains"), "Doro Wat"); // guests see menus with content
        trial(owner, days(-5), "EXPIRED");

        assertThat(patchMenu(owner, live.getId(), "{\"name\":\"Live menu\",\"description\":\"All day\"}").getStatus()).isEqualTo(200);
        assertThat(active(live.getId())).isTrue();
        MockHttpServletResponse guest = mockMvc.perform(get("/venue/" + owner.businessId() + "/menu")).andReturn().getResponse();
        assertThat(guest.getStatus()).isEqualTo(200);
        assertThat(guest.getContentAsString()).as("guests still see it").contains("Live menu");

        assertThat(patchMenu(owner, live.getId(), "{\"isActive\":false}").getStatus()).as("unpublishing is never blocked").isEqualTo(200);
        refused(patchMenu(owner, live.getId(), "{\"isActive\":true}")); // B-6: publishing again is a new publish
    }

    @Test
    void turningTheGateOnUnpublishesNothing() throws Exception {
        gate.setGate(Market.ET, false);
        Menu live = data.menu(owner, "Before");
        Menu hidden = data.menu(owner, "Hidden", false, MenuVisibility.PUBLIC);
        gate.setGate(Market.ET, true);

        assertThat(planView(owner).isObject()).isTrue(); // any read
        assertThat(active(live.getId())).isTrue();
        assertThat(active(hidden.getId())).isFalse();
    }

    // ---- order, market, isolation ----

    @Test
    void emailVerificationIsCheckedFirst() throws Exception {
        Tenant unverified = data.tenant("pg-unverified");
        verification.setEnforcePublishGate(true);
        MockHttpServletResponse r = publishNew(unverified);
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("EMAIL_NOT_VERIFIED");

        refused(publishNew(owner)); // verified: the plan check applies
    }

    @Test
    void onlyTheRestaurantsOwnMarketFlagApplies() throws Exception {
        Tenant us = data.tenant("pg-us");
        verify(us);
        jdbc.update("update business set country = 'US' where id = ?", us.businessId());
        Tenant other = data.tenant("pg-ke");
        verify(other);
        jdbc.update("update business set country = 'KE' where id = ?", other.businessId());

        assertThat(publishNew(us).getStatus()).as("ET gate doesn't apply in the US").isEqualTo(201);
        assertThat(publishNew(other).getStatus()).as("no market: not gated (B-2)").isEqualTo(201);
        refused(publishNew(owner));

        gate.setGate(Market.ET, false);
        gate.setGate(Market.US, true);
        refused(publishNew(us));
        assertThat(publishNew(owner).getStatus()).isEqualTo(201);
        assertThat(publishNew(other).getStatus()).isEqualTo(201);
    }

    @Test
    void anotherRestaurantsPlanNeverCounts() throws Exception {
        Tenant other = data.tenant("pg-other");
        subscription(jdbc, other.businessId(), "MODEL_4", 1, admin.getId());
        trial(data.tenant("pg-third"), days(10), "TRIAL");

        refused(publishNew(owner));
    }

    // ---- BE-5b: publishAllowed mirrors the decision ----

    @Test
    void publishAllowedMirrorsTheGate() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        assertThat(planView(owner).path("publishAllowed").isBoolean()).isTrue();
        assertThat(planView(owner).path("publishAllowed").asBoolean(true)).isFalse();

        subscription(jdbc, owner.businessId(), "MODEL_2", 1, admin.getId());
        assertThat(planView(owner).path("publishAllowed").asBoolean(false)).isTrue();

        Tenant old = data.tenant("pg-old");
        jdbc.update("update business set \"createdAt\" = ? where id = ?", Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")), old.businessId());
        cutOver.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(planView(old).path("publishAllowed").asBoolean(false)).as("exempt").isTrue();

        Tenant us = data.tenant("pg-us-view");
        jdbc.update("update business set country = 'US' where id = ?", us.businessId());
        assertThat(planView(us).has("publishAllowed")).as("not gated in its market: absent").isFalse();

        planRequests.setOwnerPlansVisible(false);
        assertThat(planView(owner).has("publishAllowed")).as("plans hidden: absent").isFalse();
    }
}
