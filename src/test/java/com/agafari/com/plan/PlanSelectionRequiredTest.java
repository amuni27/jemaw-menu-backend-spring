package com.agafari.com.plan;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF BE-5a (register §49: Q3 cut-over instant, Q8 skippable, D-6 reminder only, pending states count as chosen):
 * {@code GET /api/plan} tells the owner whether their restaurant must still choose how to start. {@code selectionRequired}
 * is present only while plans are visible and the cut-over instant ({@code app.monetization.plan-selection-required-from})
 * is configured. It is true only for a restaurant created at or after the cut-over that has never had a subscription or
 * trial, has no pending plan request and no payment under review (that case: {@code FinancialExceptionTest}). Read only:
 * the view never creates or changes anything, and nothing else (publishing, drafts, requests, trials) depends on it.
 */
class PlanSelectionRequiredTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanRequestService planRequests;
    @Autowired private TrialService trials;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper mapper;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp(TestInfo test) {
        admin = data.admin("sel-admin");
        owner = data.tenant("sel-owner");
        jdbc.update("update users set email_verified_at = now() where id = ?", owner.owner().getId());
        if (!test.getTestMethod().orElseThrow().getName().startsWith("setting1"))
            config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        else
            config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelectionRequiredFrom(Instant.EPOCH);
    }

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelectionRequiredFrom(null);
        planRequests.setSelfServiceEnabled(false);
        trials.setEnabled(false);
    }

    private JsonNode view(Tenant t) throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    private JsonNode required(Tenant t) throws Exception {
        return view(t).path("selectionRequired");
    }

    private Instant createdAt(Tenant t) {
        return jdbc.queryForObject("select \"createdAt\" from business where id = ?", Timestamp.class, t.businessId()).toInstant();
    }

    private int writes() {
        return jdbc.queryForObject("select (select count(*) from business_subscription) + (select count(*) from plan_request) "
                + "+ (select count(*) from admin_audit_event) + (select count(*) from trial_intent)", Integer.class);
    }

    // ---- presence ----

    @Test
    void absentUnlessTheCutOverIsConfigured() throws Exception {
        assertThat(environment.getProperty("app.monetization.plan-selection-required-from")).as("not configured by default").isNullOrEmpty();
        planRequests.setSelectionRequiredFrom(null);

        JsonNode v = view(owner);
        assertThat(v.path("visible").asBoolean()).isTrue();
        assertThat(v.has("selectionRequired")).as("response unchanged while the cut-over is not configured").isFalse();
    }

    @Test
    void absentWhilePlansAreHidden() throws Exception {
        planRequests.setOwnerPlansVisible(false);

        JsonNode v = view(owner);
        assertThat(v.path("visible").asBoolean(true)).isFalse();
        assertThat(v.has("selectionRequired")).isFalse();
    }

    // ---- new restaurants ----

    @Test
    void aNewRestaurantWithNothingChosenMustChoose() throws Exception {
        int before = writes();

        assertThat(required(owner).isBoolean()).isTrue();
        assertThat(required(owner).asBoolean()).isTrue();
        assertThat(writes()).as("the view is read only").isEqualTo(before);
    }

    @Test
    void aRestaurantRegisteredThroughTheApiMustChooseAfterSigningIn() throws Exception {
        String suffix = "sel-" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Sel Owner","email":"%s@owner.test","password":"Str0ng!Pass1","businessName":"Sel %s",
                 "businessPhone":"+2519%s","customSubdomain":"%s","open24_7":true,"country":"ET",
                 "locationUrl":"https://maps.app.goo.gl/AbC123xyz"}""".formatted(suffix, suffix,
                String.valueOf(System.nanoTime()).substring(0, 8), suffix))).andReturn().getResponse().getStatus()).isEqualTo(201);
        String token = mapper.readTree(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + suffix + "@owner.test\",\"password\":\"Str0ng!Pass1\"}")).andReturn().getResponse().getContentAsString())
                .path("token").asString();

        JsonNode v = mapper.readTree(mockMvc.perform(get("/api/plan").header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertThat(v.path("selectionRequired").asBoolean(false)).isTrue();
        assertThat(v.path("current").isMissingNode()).as("nothing was activated").isTrue();
    }

    @Test
    void aRestaurantCreatedExactlyAtTheCutOverIsNew() throws Exception {
        planRequests.setSelectionRequiredFrom(createdAt(owner));

        assertThat(required(owner).asBoolean(false)).isTrue();
    }

    // ---- existing restaurants (P-6, Q3) ----

    @Test
    void aRestaurantCreatedBeforeTheCutOverIsNeverAsked() throws Exception {
        planRequests.setSelectionRequiredFrom(createdAt(owner).plusNanos(1000));

        assertThat(required(owner).isBoolean()).isTrue();
        assertThat(required(owner).asBoolean(true)).isFalse();
    }

    @Test
    void anOldRestaurantStaysExemptWhateverItsState() throws Exception {
        jdbc.update("update business set \"createdAt\" = ? where id = ?", Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")), owner.businessId());
        planRequests.setSelectionRequiredFrom(Instant.parse("2026-11-20T00:00:00Z"));

        assertThat(required(owner).asBoolean(true)).isFalse();
    }

    // ---- chosen (pending states count) ----

    @Test
    void aCurrentSubscriptionMeansChosen() throws Exception {
        subscription(jdbc, owner.businessId(), "MODEL_2", 1, admin.getId());

        assertThat(required(owner).asBoolean(true)).isFalse();
    }

    @Test
    void aPendingRequestMeansChosenUntilItIsWithdrawn() throws Exception {
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        assertThat(required(owner).asBoolean(true)).isFalse();

        int rowVersion = jdbc.queryForObject("select row_version from plan_request where id = ?", Integer.class, id);
        assertThat(mockMvc.perform(patch("/api/plan/requests/current").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"WITHDRAWN\",\"rowVersion\":" + rowVersion + "}")).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(required(owner).asBoolean(false)).as("a withdrawn request is not a choice").isTrue();
    }

    @Test
    void startingFreeWithAdsEndsIt() throws Exception {
        planRequests.setSelfServiceEnabled(true);
        assertThat(required(owner).asBoolean(false)).isTrue();

        assertThat(mockMvc.perform(post("/api/plan/activations").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}")).andReturn().getResponse().getStatus()).isEqualTo(201);

        assertThat(required(owner).asBoolean(true)).isFalse();
    }

    @Test
    void setting1StartingTheTrialEndsIt() throws Exception {
        trials.setEnabled(true);
        assertThat(required(owner).asBoolean(false)).isTrue();

        assertThat(mockMvc.perform(post("/api/plan/trial").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedConfigVersion\":1}")).andReturn().getResponse().getStatus()).isEqualTo(201);

        assertThat(required(owner).asBoolean(true)).isFalse();
    }

    // ---- D-6: after a trial or plan ended, a reminder only (never "required" again) ----

    @Test
    void setting1AnExpiredTrialIsNotAskedAgain() throws Exception {
        Instant start = Instant.now().minus(Duration.ofDays(40));
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                UUID.randomUUID().toString(), owner.businessId(), Timestamp.from(start.plus(Duration.ofDays(30))), Timestamp.from(start), Timestamp.from(start));

        JsonNode v = view(owner);
        assertThat(v.path("trial").path("effectiveStatus").asString()).isEqualTo("EXPIRED");
        assertThat(v.path("selectionRequired").asBoolean(true)).isFalse();
    }

    @Test
    void anEndedPlanIsNotAskedAgain() throws Exception {
        String id = subscription(jdbc, owner.businessId(), "MODEL_2", 1, admin.getId());
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now(), row_version = row_version + 1 where id = ?", id);
        assertThat(view(owner).path("current").isMissingNode()).isTrue();

        assertThat(required(owner).asBoolean(true)).isFalse();
    }

    // ---- isolation and configuration ----

    @Test
    void anotherRestaurantsChoiceNeverCounts() throws Exception {
        Tenant other = data.tenant("sel-other");
        subscription(jdbc, other.businessId(), "MODEL_2", 1, admin.getId());
        pendingRequest(jdbc, data.tenant("sel-third").businessId(), "MODEL_4", 1, admin.getId());

        assertThat(required(owner).asBoolean(false)).isTrue();
        assertThat(required(other).asBoolean(true)).isFalse();
    }

    @Test
    void theCutOverIsAnIsoInstantOrEmpty() {
        assertThat(PlanRequestService.parseSelectionRequiredFrom("")).isNull();
        assertThat(PlanRequestService.parseSelectionRequiredFrom("  ")).isNull();
        assertThat(PlanRequestService.parseSelectionRequiredFrom(null)).isNull();
        assertThat(PlanRequestService.parseSelectionRequiredFrom("2026-12-01T00:00:00Z")).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThatThrownBy(() -> PlanRequestService.parseSelectionRequiredFrom("2026-12-01"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("plan-selection-required-from");
    }
}
