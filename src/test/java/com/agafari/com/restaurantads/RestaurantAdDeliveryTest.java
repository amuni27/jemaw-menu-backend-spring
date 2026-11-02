package com.agafari.com.restaurantads;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.AgafariAdDelivery;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.RestaurantAdService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static com.agafari.com.restaurantads.RestaurantAdTestSupport.action;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.suspend;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M8 (AC3–AC6, AC11, AC14, AC15): one of the restaurant's own ads is delivered in its guest menu only when the
 * restaurant ads flag is on and the existing entitlements say "restaurant ads" (current Model 3, grandfathered until its
 * approved end). Never another restaurant's ad, never for Models 1, 2 and 4 (then the response is byte-identical), never an
 * Agafari ad for Model 3, never two ads. Servable and rotation as approved. The Model 3 privacy notice flag is set whenever
 * restaurant ads apply, also without a servable ad. No impression is counted; the owner preview has no ad.
 */
class RestaurantAdDeliveryTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RestaurantAdService restaurantAds;
    @Autowired private AgafariAdDelivery agafariAds;
    @Autowired private EntitlementService entitlements;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private MonetizationClock clock;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("ra-delivery-admin");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        entitlements.setEnabled(true);
        restaurantAds.setEnabled(true);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        restaurantAds.setEnabled(false);
        agafariAds.setEnabled(false);
        grandfathering.setEnabled(false);
        clock.reset();
    }

    private Tenant restaurant(String label, String plan) {
        Tenant t = data.tenant(label);
        if (plan != null) subscription(jdbc, t.businessId(), plan, 1, admin.getId());
        return t;
    }

    private String ad(Tenant t, String title, String startsAt, String endsAt, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into restaurant_ad (id, business_id, title, body, link_url, starts_at, ends_at, schedule_time_zone, status, created_at, created_by, "
                + "updated_at, updated_by, row_version) values (?, ?, ?, 'Text', ?, ?, ?, 'Africa/Addis_Ababa', ?, now(), 'o', now(), 'o', 0)",
                id, t.businessId(), title, "https://example.test/" + id, Timestamp.from(Instant.parse(startsAt)),
                endsAt == null ? null : Timestamp.from(Instant.parse(endsAt)), status);
        return id;
    }

    private String ad(Tenant t, String title) {
        return ad(t, title, "2026-01-01T00:00:00Z", null, "ACTIVE");
    }

    private String agafariAd(String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into agafari_ad (id, title, body, link_url, starts_at, schedule_time_zone, status, created_at, created_by, updated_at, updated_by, "
                + "row_version) values (?, ?, 'Text', 'https://example.test/agafari', '2026-01-01T00:00:00Z', 'UTC', 'ACTIVE', now(), 'a', now(), 'a', 0)", id, title);
        return id;
    }

    private String menu(Tenant t) throws Exception {
        return mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String sponsoredId(String json) {
        List<String> ids = JsonPath.read(json, "$..sponsored.adId");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static boolean notice(String json) {
        List<Boolean> n = JsonPath.read(json, "$..restaurantAdsNotice");
        return !n.isEmpty() && n.get(0);
    }

    private void at(String instant) {
        clock.setOffset(Duration.between(Instant.now(), Instant.parse(instant)));
    }

    private static int expectedIndex(String businessId, LocalDate day, int n) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest((businessId + "|" + day).getBytes(StandardCharsets.UTF_8));
        int v = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        return Math.floorMod(v, n);
    }

    @Test
    void ac3AModel3RestaurantGetsOneOfItsOwnAdsWithTheNoticeAndNothingIsCounted() throws Exception {
        Tenant t = restaurant("ra-delivery-m3", "MODEL_3");
        String id = ad(t, "House special");
        String json = menu(t);
        assertThat(sponsoredId(json)).isEqualTo(id);
        assertThat((String) JsonPath.read(json, "$.sponsored.title")).isEqualTo("House special");
        assertThat((String) JsonPath.read(json, "$.sponsored.linkUrl")).isEqualTo("https://example.test/" + id); // G1: no redirect
        assertThat(notice(json)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from ad_impression_daily", Integer.class)).isZero(); // Q-M8-8
    }

    @Test
    void ac3TwoModel3RestaurantsOnlyEverSeeTheirOwnAds() throws Exception {
        Tenant a = restaurant("ra-delivery-a", "MODEL_3"), b = restaurant("ra-delivery-b", "MODEL_3");
        List<String> aAds = List.of(ad(a, "A1"), ad(a, "A2", "2026-01-02T00:00:00Z", null, "ACTIVE"));
        List<String> bAds = List.of(ad(b, "B1"), ad(b, "B2", "2026-01-02T00:00:00Z", null, "ACTIVE"), ad(b, "B3", "2026-01-03T00:00:00Z", null, "ACTIVE"));
        for (int day = 0; day < 6; day++) {
            at("2026-12-0" + (day + 1) + "T12:00:00Z");
            assertThat(aAds).contains(sponsoredId(menu(a)));
            assertThat(bAds).contains(sponsoredId(menu(b)));
        }
    }

    @Test
    void ac4NoOtherModelGetsARestaurantAdAndItsMenuIsByteIdentical() throws Exception {
        Tenant model1 = restaurant("ra-delivery-m1", "MODEL_1"), model2 = restaurant("ra-delivery-m2", "MODEL_2"),
                model4 = restaurant("ra-delivery-m4", "MODEL_4"), none = restaurant("ra-delivery-none", null);
        Tenant ended = restaurant("ra-delivery-ended", null);
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, "
                + "updated_by_user_id, ended_at, row_version) values (?, ?, 'MODEL_3', 'EXPIRED', 1, 'MANUAL', now() - interval '5 days', ?, now(), ?, now(), 0)",
                UUID.randomUUID().toString(), ended.businessId(), admin.getId(), admin.getId());
        for (Tenant t : new Tenant[] {model1, model2, model4, none, ended}) {
            ad(t, "Leftover " + t.business().getName()); // e.g. from an earlier Model 3 subscription
            String on = menu(t);
            restaurantAds.setEnabled(false);
            String off = menu(t);
            restaurantAds.setEnabled(true);
            assertThat(sponsoredId(on)).as(t.business().getName()).isNull();
            assertThat(notice(on)).as(t.business().getName()).isFalse();
            assertThat(on).as(t.business().getName()).isEqualTo(off);
        }
    }

    @Test
    void ac4AGrandfatheredModel3KeepsItsAdsUntilItsApprovedEnd() throws Exception {
        Tenant t = data.tenant("ra-delivery-grandfathered");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_3', 'ACTIVE', 1, ?, 'MANUAL', now() - interval '30 days', ?, now(), ?, 0)",
                UUID.randomUUID().toString(), t.businessId(), Timestamp.from(Instant.now().plus(Duration.ofDays(2))), admin.getId(), admin.getId());
        String id = ad(t, "Grandfathered");
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId()); // Model 3 no longer offered
        grandfathering.setEnabled(true);
        assertThat(sponsoredId(menu(t))).isEqualTo(id);
        clock.setOffset(Duration.ofDays(3));
        String after = menu(t);
        assertThat(sponsoredId(after)).isNull();
        assertThat(notice(after)).isFalse();
    }

    @Test
    void ac14WithEitherFlagOffAModel3MenuIsUnchanged() throws Exception {
        Tenant t = restaurant("ra-delivery-flags", "MODEL_3");
        ad(t, "Flagged");
        restaurantAds.setEnabled(false);
        String adsOff = menu(t);
        assertThat(sponsoredId(adsOff)).isNull();
        assertThat(notice(adsOff)).isFalse();
        restaurantAds.setEnabled(true);
        entitlements.setEnabled(false);
        String entitlementsOff = menu(t);
        assertThat(entitlementsOff).isEqualTo(adsOff);
    }

    @Test
    void ac5Model3NeverGetsAgafariAdsAndModel2StillGetsThemUnchanged() throws Exception {
        agafariAds.setEnabled(true);
        String agafari = agafariAd("Agafari coffee");
        Tenant m3 = restaurant("ra-delivery-m3-agafari", "MODEL_3");
        String m3NoAds = menu(m3);
        assertThat(sponsoredId(m3NoAds)).isNull(); // no own ad and never Agafari's
        assertThat(notice(m3NoAds)).isTrue();      // the notice applies without a servable ad
        String own = ad(m3, "Own");
        assertThat(sponsoredId(menu(m3))).isEqualTo(own);

        Tenant m2 = restaurant("ra-delivery-m2-agafari", "MODEL_2");
        ad(m2, "Leftover");
        String withM8 = menu(m2);
        assertThat(sponsoredId(withM8)).isEqualTo(agafari);
        assertThat(notice(withM8)).isFalse();
        assertThat(JsonPath.<List<Object>>read(withM8, "$..sponsored")).hasSize(1);
        restaurantAds.setEnabled(false);
        assertThat(menu(m2)).isEqualTo(withM8); // exactly as in M7
    }

    @Test
    void ac6OnlyServableAdsInTheStableDailyRotation() throws Exception {
        Tenant t = restaurant("ra-delivery-servable", "MODEL_3");
        String window = ad(t, "Window", "2026-12-01T09:00:00Z", "2026-12-02T09:00:00Z", "ACTIVE");
        ad(t, "Paused", "2026-01-01T00:00:00Z", null, "PAUSED");
        ad(t, "Archived", "2026-01-01T00:00:00Z", null, "ARCHIVED");
        at("2026-12-01T08:59:59Z");
        assertThat(sponsoredId(menu(t))).isNull();
        at("2026-12-01T09:00:00Z");
        assertThat(sponsoredId(menu(t))).isEqualTo(window); // start inclusive
        at("2026-12-02T08:59:59Z");
        assertThat(sponsoredId(menu(t))).isEqualTo(window);
        at("2026-12-02T09:00:00Z");
        assertThat(sponsoredId(menu(t))).isNull(); // end exclusive

        // suspended ads are never served
        String s = ad(t, "To suspend");
        at("2026-12-05T12:00:00Z");
        assertThat(sponsoredId(menu(t))).isEqualTo(s);
        suspend(mockMvc, data.adminBearer(admin), s, "Complaint", 0).andExpect(status().isOk());
        assertThat(sponsoredId(menu(t))).isNull();

        // rotation: stable order (starts_at, created_at, id), index by the restaurant-local day
        Tenant r = restaurant("ra-delivery-rotation", "MODEL_3");
        List<String> ordered = List.of(ad(r, "R1", "2026-01-01T00:00:00Z", null, "ACTIVE"), ad(r, "R2", "2026-01-02T00:00:00Z", null, "ACTIVE"),
                ad(r, "R3", "2026-01-03T00:00:00Z", null, "ACTIVE"));
        for (String when : List.of("2026-12-10T08:00:00Z", "2026-12-10T20:59:59Z", "2026-12-10T21:00:00Z", "2026-12-11T12:00:00Z")) {
            at(when);
            LocalDate day = LocalDate.ofInstant(Instant.parse(when), ZoneId.of("Africa/Addis_Ababa")); // the restaurant's zone (default)
            String first = sponsoredId(menu(r));
            assertThat(first).as(when).isEqualTo(ordered.get(expectedIndex(r.businessId(), day, 3)));
            assertThat(sponsoredId(menu(r))).as(when).isEqualTo(first); // stable within the day
        }
    }

    @Test
    void ac11TheOwnerPreviewHasNoAdAndTheMenuIsOtherwiseUnchanged() throws Exception {
        Tenant t = restaurant("ra-delivery-preview", "MODEL_3");
        ad(t, "Preview");
        String menuId = data.menu(t, "Lunch").getId();
        String preview = mockMvc.perform(get("/api/menus/{id}/preview", menuId).header("Authorization", data.bearer(t))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(preview).doesNotContain("sponsored").doesNotContain("restaurantAdsNotice");

        String on = menu(t);
        restaurantAds.setEnabled(false);
        String off = menu(t);
        JsonNode withAd = JSON.readTree(on), without = JSON.readTree(off);
        ((ObjectNode) withAd).remove(List.of("sponsored", "restaurantAdsNotice"));
        assertThat(withAd).isEqualTo(without); // business, menus and specials identical apart from the two fields
    }

    @Test
    void ac6AnOwnerPauseRemovesTheAdAtOnce() throws Exception {
        Tenant t = restaurant("ra-delivery-pause", "MODEL_3");
        String id = ad(t, "Pause me");
        assertThat(sponsoredId(menu(t))).isEqualTo(id);
        action(mockMvc, data.bearer(t), id, "pause", 0).andExpect(status().isOk());
        assertThat(sponsoredId(menu(t))).isNull();
        assertThat(notice(menu(t))).isTrue();
    }
}
