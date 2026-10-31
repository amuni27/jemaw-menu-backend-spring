package com.agafari.com.specials;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.entity.Special;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.jpa.repository.SpecialRepository;
import com.agafari.com.service.impl.PublicMenuServiceImpl;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-25 (checkpoint B): guests get special data only for specials that apply now (active in the restaurant's
 * time and below the dish's current regular price), on the guest menu, item detail and owner preview. Scheduled,
 * ended, paused, out-of-window or no-longer-discounted specials, and specials of hidden content, never reach guests.
 */
class PublicSpecialsTest extends AbstractIntegrationTest {

    @Autowired private SpecialRepository specials;
    @Autowired private MenuItemRepository items;
    @Autowired private MenuRepository menus;
    @Autowired private CategoryRepository categories;
    @Autowired private PublicMenuServiceImpl publicMenus;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private Tenant owner;
    private Menu menu;
    private Category mains;
    private MenuItem doro;

    @BeforeEach
    void dishOnSpecial() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        mains = data.category(menu, "Mains");
        doro = data.item(mains, "Doro Wat");
        doro.setPrice(new BigDecimal("420.00"));
        doro = items.save(doro);
        // Friday 2026-11-06 16:30 in Addis Ababa
        publicMenus.setClock(Clock.fixed(Instant.parse("2026-11-06T13:30:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void systemClock() {
        publicMenus.setClock(Clock.systemUTC());
    }

    private Special save(String startsOn, String endsOn, List<String> days, String start, String end, boolean paused) {
        Special s = new Special();
        s.setBusiness(menu.getBusiness());
        s.setMenuItem(doro);
        s.setSpecialPrice(new BigDecimal("350.00"));
        s.setNote("Happy hour");
        s.setNoteAm("ደስታ ሰዓት");
        s.setStartsOn(startsOn == null ? null : LocalDate.parse(startsOn));
        s.setEndsOn(endsOn == null ? null : LocalDate.parse(endsOn));
        s.setDaysOfWeek(days);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setPaused(paused);
        return specials.save(s);
    }

    private String guestMenu() throws Exception {
        return mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void anActiveSpecialReachesTheGuestMenuItemDetailAndPreview() throws Exception {
        save(null, null, List.of("FRIDAY"), "16:00", "18:00", false);
        String item = "$.menus[0].categories[0].items[0]";
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(item + ".price").value(420.0))
                .andExpect(jsonPath(item + ".specialPrice").value(350.0))
                .andExpect(jsonPath(item + ".specialNote").value("Happy hour"))
                .andExpect(jsonPath(item + ".specialNoteAm").value("ደስታ ሰዓት"))
                .andExpect(jsonPath(item + ".specialUntil").value("18:00"))
                .andExpect(jsonPath(item + ".specialEndsAt").value("2026-11-06T15:00:00Z"));
        mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), doro.getId()))
                .andExpect(jsonPath("$.specialPrice").value(350.0)).andExpect(jsonPath("$.specialUntil").value("18:00"));
        mockMvc.perform(get("/venue/items/{item}", doro.getId())).andExpect(jsonPath("$.specialPrice").value(350.0));
        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath(item + ".specialPrice").value(350.0));
    }

    @Test
    void scheduledEndedPausedAndOutOfWindowSpecialsNeverReachGuests() throws Exception {
        for (Runnable setup : List.<Runnable>of(
                () -> save("2026-11-07", null, List.of(), null, null, false),   // starts tomorrow
                () -> save(null, "2026-11-05", List.of(), null, null, false),   // ended yesterday
                () -> save(null, null, List.of(), null, null, true),            // paused
                () -> save(null, null, List.of("MONDAY"), null, null, false),   // not today
                () -> save(null, null, List.of(), "11:00", "14:00", false))) {  // outside the window
            specials.deleteAll();
            setup.run();
            String body = guestMenu();
            assertThat(body).contains("Doro Wat").doesNotContain("specialPrice").doesNotContain("Happy hour").doesNotContain("ደስታ ሰዓት");
            assertThat(mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), doro.getId())).andReturn().getResponse().getContentAsString())
                    .doesNotContain("specialPrice");
        }
    }

    @Test
    void aSpecialStopsApplyingWhenTheRegularPriceIsNoLongerHigher() throws Exception {
        save(null, null, List.of(), null, null, false);
        assertThat(guestMenu()).contains("specialPrice");
        doro.setPrice(new BigDecimal("350.00"));
        items.save(doro);
        assertThat(guestMenu()).doesNotContain("specialPrice").doesNotContain("Happy hour");
        doro.setPrice(new BigDecimal("400.00"));
        items.save(doro);
        assertThat(guestMenu()).contains("specialPrice");
    }

    @Test
    void specialsOfHiddenContentNeverReachGuests() throws Exception {
        save(null, null, List.of(), null, null, false);
        doro.setStatus(MenuItemStatus.UNAVAILABLE);
        items.save(doro);
        assertThat(guestMenu()).doesNotContain("Happy hour");
        mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), doro.getId())).andExpect(status().isNotFound());

        doro.setStatus(MenuItemStatus.AVAILABLE);
        items.save(doro);
        mains.setActive(false);
        categories.save(mains);
        assertThat(guestMenu()).doesNotContain("Happy hour");

        mains.setActive(true);
        categories.save(mains);
        menu.setVisibility(MenuVisibility.PRIVATE);
        menus.save(menu);
        assertThat(guestMenu()).doesNotContain("Happy hour");
    }

    @Test
    void aSoldOutDishOnSpecialStillShowsWithItsSpecial() throws Exception {
        save(null, null, List.of(), null, null, false);
        doro.setStatus(MenuItemStatus.OUT_OF_STOCK);
        items.save(doro);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].status").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].specialPrice").value(350.0));
    }

    @Test
    void specialsAddOneFixedQueryToTheGuestMenu() throws Exception {
        for (int n = 0; n < 4; n++) {
            MenuItem dish = data.item(mains, "Dish " + n);
            dish.setPrice(new BigDecimal("100.00"));
            dish = items.save(dish);
            Special s = new Special();
            s.setBusiness(menu.getBusiness());
            s.setMenuItem(dish);
            s.setSpecialPrice(new BigDecimal("50.00"));
            s.setDaysOfWeek(List.of());
            specials.save(s);
        }
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            assertThat(guestMenu()).contains("specialPrice");
            long withFour = stats.getPrepareStatementCount();
            specials.deleteAll();
            stats.clear();
            guestMenu();
            assertThat(withFour).isEqualTo(stats.getPrepareStatementCount()); // same count with 0 or 4 specials
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }
}
