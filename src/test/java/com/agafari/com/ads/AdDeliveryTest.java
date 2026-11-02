package com.agafari.com.ads;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.AgafariAdDelivery;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.ads.AdTestSupport.action;
import static com.agafari.com.ads.AdTestSupport.body;
import static com.agafari.com.ads.AdTestSupport.createId;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M7 (AC3–AC6, AC8–AC12, C3, C5, C6, C8): one Agafari ad is delivered inside the guest menu of a restaurant whose
 * existing entitlements say "Agafari ads" (Model 2, grandfathered until its approved end), only with the ads flag on. Never
 * for any other restaurant, and then the response is byte-identical to today's. Daily rotation over the current servable
 * inventory in a stable order; served impressions per ad, restaurant and restaurant-local day; a counting failure never
 * affects delivery; the owner preview has no ad.
 */
class AdDeliveryTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgafariAdDelivery delivery;
    @Autowired private EntitlementService entitlements;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private MonetizationClock clock;

    private User admin;
    private String bearer;

    @BeforeEach
    void setUp() {
        admin = data.admin("ads-delivery");
        bearer = data.adminBearer(admin);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        entitlements.setEnabled(true);
        delivery.setEnabled(true);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        delivery.setEnabled(false);
        grandfathering.setEnabled(false);
        clock.reset();
        jdbc.execute("drop trigger if exists test_failing_impression on ad_impression_daily");
        jdbc.execute("drop function if exists test_failing_impression()");
    }

    private String ad(String title) throws Exception {
        return createId(mockMvc, bearer, body(title, "Text " + title, "https://example.test/" + title.toLowerCase().replace(' ', '-'), "2026-01-01T00:00", null, "UTC"));
    }

    private Tenant restaurant(String label, String plan) {
        Tenant t = data.tenant(label);
        if (plan != null) subscription(jdbc, t.businessId(), plan, 1, admin.getId());
        return t;
    }

    private String menu(Tenant t) throws Exception {
        return mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String sponsoredId(String json) {
        List<String> ids = JsonPath.read(json, "$..sponsored.adId");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long served(String adId, String businessId) {
        return jdbc.queryForObject("select coalesce(sum(impression_count), 0) from ad_impression_daily where ad_id = ? and business_id = ?", Long.class, adId, businessId);
    }

    /** The approved rotation rule (C3): SHA-256 of "businessId|local date", first 4 bytes as an int, modulo the servable count. */
    private static int expectedIndex(String businessId, LocalDate day, int n) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest((businessId + "|" + day).getBytes(StandardCharsets.UTF_8));
        int v = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        return Math.floorMod(v, n);
    }

    @Test
    void ac4AModel2RestaurantGetsOneAdWithTheApprovedFieldsAndTheLinkUnchanged() throws Exception {
        String id = ad("Coffee week");
        Tenant t = restaurant("ads-model2", "MODEL_2");
        String json = menu(t);
        assertThat(sponsoredId(json)).isEqualTo(id);
        assertThat((String) JsonPath.read(json, "$.sponsored.title")).isEqualTo("Coffee week");
        assertThat((String) JsonPath.read(json, "$.sponsored.linkUrl")).isEqualTo("https://example.test/coffee-week"); // G1: no redirect
        assertThat(served(id, t.businessId())).isEqualTo(1);
    }

    @Test
    void ac5NoOtherRestaurantGetsAnAdAndItsMenuIsByteIdentical() throws Exception {
        ad("Only ad");
        Tenant model1 = restaurant("ads-model1", "MODEL_1");
        Tenant model3 = restaurant("ads-model3", "MODEL_3");
        Tenant model4 = restaurant("ads-model4", "MODEL_4");
        Tenant none = restaurant("ads-none", null);
        Tenant trial = data.tenant("ads-trial");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, now() + interval '10 days', 'TRIAL', now(), 'system', now(), 'system', 0)",
                UUID.randomUUID().toString(), trial.businessId());
        Tenant ended = restaurant("ads-ended-model2", null);
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, "
                + "updated_by_user_id, ended_at, row_version) values (?, ?, 'MODEL_2', 'EXPIRED', 1, 'MANUAL', now() - interval '5 days', ?, now(), ?, now(), 0)",
                UUID.randomUUID().toString(), ended.businessId(), admin.getId(), admin.getId());
        for (Tenant t : new Tenant[] {model1, model3, model4, none, trial, ended}) {
            String on = menu(t);
            delivery.setEnabled(false);
            String off = menu(t);
            delivery.setEnabled(true);
            assertThat(on).as(t.businessId()).isEqualTo(off);
            assertThat(on).doesNotContain("sponsored");
        }
        assertThat(jdbc.queryForObject("select count(*) from ad_impression_daily", Integer.class)).isZero();
    }

    @Test
    void ac14WithEitherFlagOffOrNoServableAdModel2GetsNoAd() throws Exception {
        Tenant t = restaurant("ads-flags", "MODEL_2");
        assertThat(sponsoredId(menu(t))).isNull(); // no ad in the inventory
        String id = ad("Flags");
        delivery.setEnabled(false);
        assertThat(sponsoredId(menu(t))).isNull();
        delivery.setEnabled(true);
        entitlements.setEnabled(false);
        assertThat(sponsoredId(menu(t))).isNull();
        entitlements.setEnabled(true);
        assertThat(sponsoredId(menu(t))).isEqualTo(id);
    }

    @Test
    void ac4GrandfatheredModel2KeepsAdsUntilItsApprovedEnd() throws Exception {
        String id = ad("Grandfathered");
        Tenant t = data.tenant("ads-grandfathered");
        String sub = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_2', 'ACTIVE', 1, ?, 'MANUAL', now() - interval '30 days', ?, now(), ?, 0)",
                sub, t.businessId(), Timestamp.from(Instant.now().plus(Duration.ofDays(2))), admin.getId(), admin.getId());
        config(jdbc, 2, "SETTING_2", false, true, true, admin.getId()); // Model 2 no longer offered
        assertThat(sponsoredId(menu(t))).isEqualTo(id);
        grandfathering.setEnabled(true);
        assertThat(sponsoredId(menu(t))).isEqualTo(id); // before its end
        clock.setOffset(Duration.ofDays(3));
        assertThat(sponsoredId(menu(t))).isNull(); // effectively ended (transitions on): existing entitlement behaviour
    }

    @Test
    void ac3PausedArchivedNotYetStartedAndExpiredAdsAreNeverServed() throws Exception {
        Tenant t = restaurant("ads-servable", "MODEL_2");
        String id = createId(mockMvc, bearer, body("Window", "Text", "https://example.test", "2026-12-01T09:00", "2026-12-02T09:00", "UTC"));
        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2026-12-01T08:59:59Z")));
        assertThat(sponsoredId(menu(t))).isNull();
        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2026-12-01T09:00:00Z")));
        assertThat(sponsoredId(menu(t))).isEqualTo(id); // start inclusive
        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2026-12-02T08:59:59Z")));
        assertThat(sponsoredId(menu(t))).isEqualTo(id);
        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2026-12-02T09:00:00Z")));
        assertThat(sponsoredId(menu(t))).isNull(); // end exclusive
        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2026-12-01T12:00:00Z")));
        action(mockMvc, bearer, id, "pause", 0).andExpect(status().isOk());
        assertThat(sponsoredId(menu(t))).isNull();
        action(mockMvc, bearer, id, "resume", 1).andExpect(status().isOk());
        assertThat(sponsoredId(menu(t))).isEqualTo(id);
        action(mockMvc, bearer, id, "archive", 2).andExpect(status().isOk());
        assertThat(sponsoredId(menu(t))).isNull();
    }

    @Test
    void ac6RotationIsStableForADayAndFollowsTheCurrentServableInventory() throws Exception {
        List<String> ids = List.of(ad("Rotation A"), ad("Rotation B"), ad("Rotation C"));
        Tenant t = restaurant("ads-rotation", "MODEL_2");
        jdbc.update("update business set time_zone = 'Africa/Addis_Ababa' where id = ?", t.businessId());
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Addis_Ababa"));
        List<String> order = jdbc.queryForList("select id from agafari_ad order by starts_at, created_at, id", String.class);
        String expected = order.get(expectedIndex(t.businessId(), today, 3));
        assertThat(sponsoredId(menu(t))).isEqualTo(expected);
        assertThat(sponsoredId(menu(t))).isEqualTo(expected); // same day, same inventory

        int version = jdbc.queryForObject("select row_version from agafari_ad where id = ?", Integer.class, expected);
        action(mockMvc, bearer, expected, "pause", version).andExpect(status().isOk());
        List<String> remaining = order.stream().filter(i -> !i.equals(expected)).toList();
        String now = sponsoredId(menu(t));
        assertThat(now).isEqualTo(remaining.get(expectedIndex(t.businessId(), today, 2))).isNotEqualTo(expected);
        assertThat(ids).contains(now);

        clock.setOffset(Duration.ofDays(1)); // the next local day uses the next day's key
        assertThat(sponsoredId(menu(t))).isEqualTo(remaining.get(expectedIndex(t.businessId(), today.plusDays(1), 2)));
    }

    @Test
    void ac8ServedImpressionsUseTheRestaurantLocalDayAndCountConcurrentDeliveries() throws Exception {
        String id = ad("Counted");
        Tenant chicago = restaurant("ads-chicago", "MODEL_2");
        Tenant addis = restaurant("ads-addis", "MODEL_2");
        jdbc.update("update business set time_zone = 'America/Chicago' where id = ?", chicago.businessId());
        jdbc.update("update business set time_zone = 'Africa/Addis_Ababa' where id = ?", addis.businessId());
        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2026-11-03T03:00:00Z"))); // Chicago Nov 2 21:00, Addis Nov 3 06:00
        menu(chicago);
        menu(addis);
        assertThat(jdbc.queryForObject("select view_date::text from ad_impression_daily where business_id = ?", String.class, chicago.businessId())).isEqualTo("2026-11-02");
        assertThat(jdbc.queryForObject("select view_date::text from ad_impression_daily where business_id = ?", String.class, addis.businessId())).isEqualTo("2026-11-03");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> menu(addis)));
            for (Future<String> f : results) assertThat(sponsoredId(f.get(30, TimeUnit.SECONDS))).isEqualTo(id);
        } finally {
            pool.shutdownNow();
        }
        assertThat(served(id, addis.businessId())).isEqualTo(21);
        mockMvc.perform(get("/api/admin/ads").header("Authorization", bearer)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$[0].servedImpressions").value(22));
    }

    @Test
    void ac9WhenCountingFailsTheMenuAndTheSelectedAdAreStillDelivered() throws Exception {
        String id = ad("Resilient");
        Tenant t = restaurant("ads-count-fails", "MODEL_2");
        jdbc.execute("""
                create function test_failing_impression() returns trigger language plpgsql as $$
                begin
                    raise exception 'test: impression store unavailable';
                end
                $$""");
        jdbc.execute("create trigger test_failing_impression before insert or update on ad_impression_daily for each row execute function test_failing_impression()");
        String json = menu(t); // 200
        assertThat(sponsoredId(json)).isEqualTo(id);
        assertThat(served(id, t.businessId())).isZero();
    }

    @Test
    void ac10ac12TheOwnerPreviewHasNoAdAndSpecialsAndTheMenuAreUnchanged() throws Exception {
        ad("Preview");
        Tenant t = restaurant("ads-preview", "MODEL_2");
        String menuId = data.menu(t, "Lunch").getId();
        String preview = mockMvc.perform(get("/api/menus/{id}/preview", menuId).header("Authorization", data.bearer(t))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(preview).doesNotContain("sponsored");
        assertThat(jdbc.queryForObject("select count(*) from ad_impression_daily", Integer.class)).isZero();

        String on = menu(t);
        delivery.setEnabled(false);
        String off = menu(t);
        JsonNode withAd = JSON.readTree(on), without = JSON.readTree(off);
        ((com.fasterxml.jackson.databind.node.ObjectNode) withAd).remove("sponsored");
        assertThat(withAd).isEqualTo(without); // business, menus and specials identical apart from the one block
    }
}
