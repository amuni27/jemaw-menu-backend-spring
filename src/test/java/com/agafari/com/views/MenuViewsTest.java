package com.agafari.com.views;

import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.service.MenuViewCounter;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-24 (D-S2-24-1 option A): a successful guest menu load adds 1 to the restaurant's count for its local day.
 * Only the business id, the date and a number are stored; counting never affects the guest response. Owners read
 * their own daily totals.
 */
class MenuViewsTest extends AbstractIntegrationTest {

    /** 2026-11-01 21:30 UTC = 2026-11-02 00:30 in Addis Ababa = 2026-11-01 16:30 in New York (EST). */
    private static final Instant LATE_EVENING_UTC = Instant.parse("2026-11-01T21:30:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MenuViewCounter counter;
    @Autowired private BusinessRepository businesses;

    private Tenant cafe;

    @BeforeEach
    void restaurantWithALiveMenu() {
        cafe = data.tenant("views-cafe");
        data.item(data.category(data.menu(cafe, "Lunch"), "Mains"), "Shiro");
    }

    @AfterEach
    void systemClock() {
        counter.setClock(Clock.systemUTC());
    }

    private Integer viewsOn(String businessId, LocalDate date) {
        List<Integer> rows = jdbc.queryForList("select view_count from menu_view_daily where business_id = ? and view_date = ?",
                Integer.class, businessId, date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int totalViews() {
        return jdbc.queryForObject("select coalesce(sum(view_count), 0) from menu_view_daily", Integer.class);
    }

    private void fixClock(Instant instant) {
        counter.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    // ---- AC1: a successful guest menu load counts exactly once ----

    @Test
    void ac1AGuestMenuLoadAddsExactlyOneForTodayInTheRestaurantsZone() throws Exception {
        fixClock(LATE_EVENING_UTC);
        mockMvc.perform(get("/venue/{id}/menu", cafe.businessId())).andExpect(status().isOk());
        assertThat(viewsOn(cafe.businessId(), LocalDate.parse("2026-11-02"))).isEqualTo(1);

        mockMvc.perform(get("/venue/{id}/menu", cafe.businessId())).andExpect(status().isOk());
        assertThat(viewsOn(cafe.businessId(), LocalDate.parse("2026-11-02"))).isEqualTo(2);
        assertThat(totalViews()).isEqualTo(2);
    }

    @Test
    void ac1ARestaurantWithoutALiveMenuStillCountsBecauseTheMenuPageLoaded() throws Exception {
        Tenant empty = data.tenant("views-empty");
        mockMvc.perform(get("/venue/{id}/menu", empty.businessId())).andExpect(status().isOk());
        assertThat(totalViews()).isEqualTo(1);
    }

    // ---- AC2: nothing else counts ----

    @Test
    void ac2NotFoundItemDetailsSharePageOwnerPreviewAndOwnerApisDontCount() throws Exception {
        var item = data.item(data.category(data.menu(cafe, "Dinner"), "Grill"), "Tibs");
        String owner = data.bearer(cafe);

        mockMvc.perform(get("/venue/{id}/menu", "no-such-business")).andExpect(status().isNotFound());
        mockMvc.perform(get("/venue/{id}/items/{item}", cafe.businessId(), item.getId())).andExpect(status().isOk());
        mockMvc.perform(get("/venue/{id}/share", cafe.businessId())).andExpect(status().isOk());
        mockMvc.perform(get("/api/menus").header("Authorization", owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/menus/{menu}/preview", item.getMenu().getId()).header("Authorization", owner))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/business/{id}/menu-views", cafe.businessId()).header("Authorization", owner))
                .andExpect(status().isOk());

        assertThat(totalViews()).isZero();
    }

    // ---- AC3: the restaurant's local date ----

    @Test
    void ac3ViewsCountTowardTheRestaurantsLocalDate() throws Exception {
        Tenant ny = data.tenant("views-ny");
        Business b = businesses.findById(ny.businessId()).orElseThrow();
        b.setCountry("US");
        b.setTimeZone("America/New_York");
        businesses.save(b);
        fixClock(LATE_EVENING_UTC);

        mockMvc.perform(get("/venue/{id}/menu", cafe.businessId())).andExpect(status().isOk());
        mockMvc.perform(get("/venue/{id}/menu", ny.businessId())).andExpect(status().isOk());

        assertThat(viewsOn(cafe.businessId(), LocalDate.parse("2026-11-02"))).isEqualTo(1); // Addis: already Nov 2
        assertThat(viewsOn(ny.businessId(), LocalDate.parse("2026-11-01"))).isEqualTo(1);   // New York: still Nov 1
        assertThat(viewsOn(cafe.businessId(), LocalDate.parse("2026-11-01"))).isNull();
    }

    // ---- AC4: counting never breaks the guest menu ----

    @Test
    void ac4ACountingFailureStillReturnsTheGuestMenu() throws Exception {
        jdbc.execute("alter table menu_view_daily rename to menu_view_daily_hidden");
        try {
            mockMvc.perform(get("/venue/{id}/menu", cafe.businessId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.business.id").value(cafe.businessId()));
        } finally {
            jdbc.execute("alter table menu_view_daily_hidden rename to menu_view_daily");
        }
        assertThat(totalViews()).isZero();
    }

    // ---- AC5: concurrent opens are all counted ----

    @Test
    void ac5ConcurrentOpensAreAllCounted() throws Exception {
        fixClock(LATE_EVENING_UTC);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                done.add(pool.submit(() -> counter.record(cafe.businessId(), "Africa/Addis_Ababa")));
            }
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
        assertThat(viewsOn(cafe.businessId(), LocalDate.parse("2026-11-02"))).isEqualTo(200);
    }

    // ---- AC6: owner-only, own restaurant ----

    @Test
    void ac6TheOwnerReadsOnlyTheirOwnRestaurant() throws Exception {
        Tenant other = data.tenant("views-other");
        mockMvc.perform(get("/api/business/{id}/menu-views", other.businessId()).header("Authorization", data.bearer(cafe)))
                .andExpect(status().isForbidden()); // business-id path endpoints answer 403 for another business (S1-06)
        mockMvc.perform(get("/api/business/{id}/menu-views", cafe.businessId()))
                .andExpect(status().isUnauthorized());
    }

    // ---- AC7: consecutive days ending today, zeros included ----

    @Test
    void ac7TheApiReturnsConsecutiveDaysEndingTodayWithZeros() throws Exception {
        fixClock(LATE_EVENING_UTC); // today in Addis = 2026-11-02
        jdbc.update("insert into menu_view_daily (business_id, view_date, view_count) values (?, ?, ?), (?, ?, ?), (?, ?, ?)",
                cafe.businessId(), LocalDate.parse("2026-11-02"), 5,
                cafe.businessId(), LocalDate.parse("2026-10-31"), 3,
                cafe.businessId(), LocalDate.parse("2026-10-20"), 9); // outside a 7-day window

        String body = mockMvc.perform(get("/api/business/{id}/menu-views", cafe.businessId()).param("days", "7")
                        .header("Authorization", data.bearer(cafe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Africa/Addis_Ababa"))
                .andExpect(jsonPath("$.today").value(5))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andReturn().getResponse().getContentAsString();

        List<String> dates = JsonPath.read(body, "$.days[*].date");
        List<Integer> views = JsonPath.read(body, "$.days[*].views");
        assertThat(dates).containsExactly("2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02");
        assertThat(views).containsExactly(0, 0, 0, 0, 3, 0, 5);
    }

    @Test
    void ac7DefaultIs30DaysAndOutOfRangeIsRejected() throws Exception {
        String owner = data.bearer(cafe);
        mockMvc.perform(get("/api/business/{id}/menu-views", cafe.businessId()).header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(0))
                .andExpect(jsonPath("$.days.length()").value(30));
        for (String bad : List.of("0", "91", "-1", "abc")) {
            mockMvc.perform(get("/api/business/{id}/menu-views", cafe.businessId()).param("days", bad).header("Authorization", owner))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- AC9: no personal data stored ----

    @Test
    void ac9TheTableHoldsOnlyTheBusinessTheDateAndTheCount() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'menu_view_daily' order by column_name", String.class);
        assertThat(columns).containsExactly("business_id", "view_count", "view_date");
    }
}
