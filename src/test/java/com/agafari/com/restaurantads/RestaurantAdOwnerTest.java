package com.agafari.com.restaurantads;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.RestaurantAdService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.agafari.com.restaurantads.RestaurantAdTestSupport.ADMIN;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.FIELD;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.OWNER;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.action;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.body;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.create;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.createId;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.list;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.update;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.valid;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.with;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M8 (AC1, AC2, AC8, AC11): a current Model 3 owner manages their own ads within the approved content limits, with
 * schedules in the restaurant's own time zone, optimistic edits, pause, resume and archive (never deleted), every change and
 * rejection audited. Tenant isolation: the restaurant comes only from the token; another restaurant's ad is 404 on every
 * endpoint and stays unchanged. The 20 non-archived ads safety cap holds under concurrent creates.
 */
class RestaurantAdOwnerTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementService entitlements;
    @Autowired private RestaurantAdService restaurantAds;

    private User admin;
    private Tenant a;
    private Tenant b;
    private String bearerA;
    private String bearerB;

    @BeforeEach
    void setUp() {
        admin = data.admin("ra-owner-admin");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        entitlements.setEnabled(true);
        restaurantAds.setEnabled(true);
        a = data.tenant("ra-owner-a");
        b = data.tenant("ra-owner-b");
        subscription(jdbc, a.businessId(), "MODEL_3", 1, admin.getId());
        subscription(jdbc, b.businessId(), "MODEL_3", 1, admin.getId());
        bearerA = data.bearer(a);
        bearerB = data.bearer(b);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        restaurantAds.setEnabled(false);
    }

    private int audits(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ?", Integer.class, action);
    }

    private void insertAds(String businessId, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("insert into restaurant_ad (id, business_id, title, body, link_url, starts_at, schedule_time_zone, status, created_at, created_by, "
                    + "updated_at, updated_by, row_version) values (?, ?, ?, 'Text', 'https://e.test', now(), 'Africa/Addis_Ababa', 'PAUSED', now(), 'o', now(), 'o', 0)",
                    UUID.randomUUID().toString(), businessId, "Seed " + i);
        }
    }

    @Test
    void ac1AModel3OwnerCreatesAnAdInTheRestaurantTimeZoneAndItIsAudited() throws Exception {
        String json = with(with(valid("Coffee week"), "titleAm", "የቡና ሳምንት"), "bodyAm", "አዲሱን ይሞክሩ");
        create(mockMvc, bearerA, json.replace("2026-01-01T00:00", "2026-12-01T09:00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Coffee week"))
                .andExpect(jsonPath("$.titleAm").value("የቡና ሳምንት"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scheduleTimeZone").value("Africa/Addis_Ababa"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-01T06:00:00Z")) // 09:00 in Addis Ababa (UTC+03:00)
                .andExpect(jsonPath("$.startLocal").value("2026-12-01T09:00"))
                .andExpect(jsonPath("$.startOffset").value("+03:00"))
                .andExpect(jsonPath("$.rowVersion").value(0))
                .andExpect(jsonPath("$.servedImpressions").doesNotExist()); // Q-M8-8: nothing counted
        assertThat(audits("RESTAURANT_AD_CREATED")).isEqualTo(1);
        list(mockMvc, bearerA).andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.cap").value(20))
                .andExpect(jsonPath("$.nonArchivedCount").value(1))
                .andExpect(jsonPath("$.timeZone").value("Africa/Addis_Ababa"))
                .andExpect(jsonPath("$.ads[0].title").value("Coffee week"));
    }

    @Test
    void ac1ContentRulesStaleEditsPauseResumeArchiveAndNoDelete() throws Exception {
        create(mockMvc, bearerA, body("x".repeat(61), "ok", "https://example.test", "2026-12-01T09:00", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("title")).value(hasItem("Enter a title (at most 60 characters)")));
        create(mockMvc, bearerA, body("ok", "y".repeat(201), "https://example.test", "2026-12-01T09:00", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("body")).value(hasItem("Enter the text (at most 200 characters)")));
        for (String link : new String[] {"http://example.test", "javascript:alert(1)", "data:text/html,hi", "https://", "example.test"}) {
            create(mockMvc, bearerA, body("ok", "ok", link, "2026-12-01T09:00", null)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath(FIELD.formatted("linkUrl")).exists());
        }
        create(mockMvc, bearerA, body("ok", "ok", "https://example.test", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startLocal")).value(hasItem("Enter a start date and time")));
        create(mockMvc, bearerA, body("ok", "ok", "https://example.test", "2026-12-01T09:00", "2026-12-01T09:00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("endLocal")).value(hasItem("The end must be after the start")));
        assertThat(audits("RESTAURANT_AD_CHANGE_REJECTED")).isGreaterThanOrEqualTo(9);

        String id = createId(mockMvc, bearerA, valid("Lunch deal"));
        update(mockMvc, bearerA, id, with(valid("Lunch deal 2"), "rowVersion", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Lunch deal 2")).andExpect(jsonPath("$.rowVersion").value(1));
        update(mockMvc, bearerA, id, with(valid("Stale"), "rowVersion", 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_VERSION_CONFLICT"));
        action(mockMvc, bearerA, id, "pause", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        action(mockMvc, bearerA, id, "resume", 2).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        action(mockMvc, bearerA, id, "resume", 3).andExpect(status().isBadRequest()); // not paused
        mockMvc.perform(delete(OWNER + "/{id}", id).header("Authorization", bearerA)).andExpect(status().is4xxClientError());
        action(mockMvc, bearerA, id, "archive", 3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        update(mockMvc, bearerA, id, with(valid("After"), "rowVersion", 4)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_ARCHIVED"));
        assertThat(jdbc.queryForObject("select count(*) from restaurant_ad where id = ?", Integer.class, id)).isEqualTo(1);
        for (String act : List.of("RESTAURANT_AD_UPDATED", "RESTAURANT_AD_PAUSED", "RESTAURANT_AD_RESUMED", "RESTAURANT_AD_ARCHIVED")) {
            assertThat(audits(act)).as(act).isEqualTo(1);
        }
    }

    @Test
    void ac8SchedulesUseTheRestaurantTimeZoneWithTheApprovedDstRules() throws Exception {
        jdbc.update("update business set time_zone = 'America/Chicago' where id = ?", a.businessId());
        create(mockMvc, bearerA, body("Fall back", "Twice", "https://example.test", "2026-11-01T01:30", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startLocal")).value(hasItem("This time occurs twice in America/Chicago; choose the offset (-05:00 or -06:00)")));
        create(mockMvc, bearerA, body("Spring forward", "Gap", "https://example.test", "2026-03-08T02:30", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startLocal"))
                        .value(hasItem("This time doesn't exist in America/Chicago (clocks move forward); choose another time")));
        // a time zone in the request is ignored: the restaurant's own zone applies
        create(mockMvc, bearerA, with(with(body("Fall back", "Twice", "https://example.test", "2026-11-01T01:30", null), "startOffset", "-06:00"), "timeZone", "UTC"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsAt").value("2026-11-01T07:30:00Z"))
                .andExpect(jsonPath("$.scheduleTimeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.startLocal").value("2026-11-01T01:30"))
                .andExpect(jsonPath("$.startOffset").value("-06:00"));
    }

    @Test
    void ac2AnotherRestaurantsAdIsNotFoundOnEveryEndpointAndStaysUnchanged() throws Exception {
        String id = createId(mockMvc, bearerA, valid("Only A"));
        update(mockMvc, bearerB, id, with(valid("Hijack"), "rowVersion", 0)).andExpect(status().isNotFound());
        for (String act : List.of("pause", "resume", "archive", "image/remove")) {
            action(mockMvc, bearerB, id, act, 0).andExpect(status().isNotFound());
        }
        mockMvc.perform(post(OWNER + "/{id}/image/presign", id).header("Authorization", bearerB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\"}")).andExpect(status().isNotFound());
        mockMvc.perform(post(OWNER + "/{id}/image/confirm", id).header("Authorization", bearerB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"restaurant-ad-asset/" + b.businessId() + "/" + id + "/main.png\",\"imageAlt\":\"x\",\"rowVersion\":0}"))
                .andExpect(status().isNotFound());
        list(mockMvc, bearerB).andExpect(status().isOk()).andExpect(jsonPath("$.ads").isEmpty());
        assertThat(jdbc.queryForMap("select title, status, row_version from restaurant_ad where id = ?", id))
                .containsEntry("title", "Only A").containsEntry("status", "ACTIVE").containsEntry("row_version", 0);

        // a businessId in the body is ignored: the ad belongs to the token's restaurant
        String own = createId(mockMvc, bearerA, with(valid("Body business"), "businessId", b.businessId()));
        assertThat(jdbc.queryForObject("select business_id from restaurant_ad where id = ?", String.class, own)).isEqualTo(a.businessId());
    }

    @Test
    void ac2UnauthenticatedAndWrongRoleRequestsAreRefused() throws Exception {
        mockMvc.perform(get(OWNER)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(OWNER).header("Authorization", data.adminBearer(admin))).andExpect(status().isForbidden());
        create(mockMvc, data.adminBearer(admin), valid("Admin")).andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN).header("Authorization", bearerA)).andExpect(status().isForbidden());
    }

    @Test
    void ac11TheSafetyCapIsTwentyNonArchivedAdsAndArchivingFreesAPlace() throws Exception {
        insertAds(a.businessId(), 19);
        String twentieth = createId(mockMvc, bearerA, valid("Twentieth"));
        create(mockMvc, bearerA, valid("Twenty-first")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTAURANT_AD_LIMIT"));
        assertThat(audits("RESTAURANT_AD_CHANGE_REJECTED")).isEqualTo(1);
        createId(mockMvc, bearerB, valid("Other restaurant")); // the cap is per restaurant
        action(mockMvc, bearerA, twentieth, "archive", 0).andExpect(status().isOk());
        create(mockMvc, bearerA, valid("After archive")).andExpect(status().isCreated());
        list(mockMvc, bearerA).andExpect(jsonPath("$.nonArchivedCount").value(20));
    }

    @Test
    void ac11ConcurrentCreatesNeverExceedTheCap() throws Exception {
        insertAds(a.businessId(), 18);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String title = "Race " + i;
                results.add(pool.submit((Callable<Integer>) () -> create(mockMvc, bearerA, valid(title)).andReturn().getResponse().getStatus()));
            }
            int created = 0;
            for (Future<Integer> f : results) if (f.get() == 201) created++;
            assertThat(created).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from restaurant_ad where business_id = ? and status <> 'ARCHIVED'", Integer.class, a.businessId()))
                .isEqualTo(20);
    }
}
