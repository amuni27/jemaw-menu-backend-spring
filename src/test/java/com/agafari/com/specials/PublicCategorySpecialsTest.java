package com.agafari.com.specials;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.CategorySpecial;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.entity.Special;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.CategorySpecialRepository;
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
 * AGF-S2-36 (checkpoint B): an active category special discounts the guest-visible dishes of its category (half-up to
 * 2 decimals, D-CS-6). An applicable dish special wins and the two never stack (D-CS-4). Inactive category specials
 * and hidden content never reach guests. Prices follow each dish's current price and category.
 */
class PublicCategorySpecialsTest extends AbstractIntegrationTest {

    @Autowired private CategorySpecialRepository categorySpecials;
    @Autowired private SpecialRepository specials;
    @Autowired private MenuItemRepository items;
    @Autowired private MenuRepository menus;
    @Autowired private CategoryRepository categories;
    @Autowired private PublicMenuServiceImpl publicMenus;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private static final String CAT = "$.menus[0].categories[0]";
    private static final String DORO = CAT + ".items[0]";
    private static final String TIBS = CAT + ".items[1]";

    private Tenant owner;
    private Menu menu;
    private Category mains;
    private MenuItem doro;
    private MenuItem tibs;

    @BeforeEach
    void discountedCategory() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        mains = data.category(menu, "Mains");
        doro = priced(data.item(mains, "Doro Wat"), "420.00", 0);
        tibs = priced(data.item(mains, "Tibs"), "333.33", 1);
        // Friday 2026-11-06 16:30 in Addis Ababa
        publicMenus.setClock(Clock.fixed(Instant.parse("2026-11-06T13:30:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void systemClock() {
        publicMenus.setClock(Clock.systemUTC());
    }

    private MenuItem priced(MenuItem item, String price, int sortOrder) {
        item.setPrice(new BigDecimal(price));
        item.setSortOrder(sortOrder);
        return items.save(item);
    }

    private CategorySpecial save(Category category, int percent, String startsOn, String endsOn, List<String> days, String start, String end, boolean paused) {
        CategorySpecial s = new CategorySpecial();
        s.setBusiness(menu.getBusiness());
        s.setCategory(category);
        s.setDiscountPercent(percent);
        s.setNote("Sweet hour");
        s.setNoteAm("ጣፋጭ ሰዓት");
        s.setStartsOn(startsOn == null ? null : LocalDate.parse(startsOn));
        s.setEndsOn(endsOn == null ? null : LocalDate.parse(endsOn));
        s.setDaysOfWeek(days);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setPaused(paused);
        return categorySpecials.save(s);
    }

    private CategorySpecial active(int percent) {
        return save(mains, percent, null, null, List.of("FRIDAY"), "16:00", "18:00", false);
    }

    private Special dishSpecial(MenuItem dish, String price, boolean paused) {
        Special s = new Special();
        s.setBusiness(menu.getBusiness());
        s.setMenuItem(dish);
        s.setSpecialPrice(new BigDecimal(price));
        s.setNote("Happy hour");
        s.setDaysOfWeek(List.of());
        s.setPaused(paused);
        return specials.save(s);
    }

    private String guestMenu() throws Exception {
        return mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void anActiveCategorySpecialDiscountsItsDishesOnTheGuestMenuItemDetailAndPreview() throws Exception {
        active(20);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(DORO + ".price").value(420.0))
                .andExpect(jsonPath(DORO + ".specialPrice").value(336.0))
                .andExpect(jsonPath(DORO + ".specialType").value("CATEGORY"))
                .andExpect(jsonPath(DORO + ".specialPercent").value(20))
                .andExpect(jsonPath(DORO + ".specialNote").value("Sweet hour"))
                .andExpect(jsonPath(DORO + ".specialNoteAm").value("ጣፋጭ ሰዓት"))
                .andExpect(jsonPath(DORO + ".specialUntil").value("18:00"))
                .andExpect(jsonPath(DORO + ".specialEndsAt").value("2026-11-06T15:00:00Z"))
                .andExpect(jsonPath(TIBS + ".specialPrice").value(266.66)) // 333.33 × 0.8 = 266.664
                .andExpect(jsonPath(CAT + ".special.percent").value(20))
                .andExpect(jsonPath(CAT + ".special.note").value("Sweet hour"))
                .andExpect(jsonPath(CAT + ".special.noteAm").value("ጣፋጭ ሰዓት"))
                .andExpect(jsonPath(CAT + ".special.until").value("18:00"))
                .andExpect(jsonPath(CAT + ".special.endsAt").value("2026-11-06T15:00:00Z"));
        mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), doro.getId()))
                .andExpect(jsonPath("$.specialPrice").value(336.0)).andExpect(jsonPath("$.specialType").value("CATEGORY"));
        mockMvc.perform(get("/venue/items/{item}", tibs.getId())).andExpect(jsonPath("$.specialPrice").value(266.66));
        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath(DORO + ".specialPrice").value(336.0))
                .andExpect(jsonPath(CAT + ".special.percent").value(20));
    }

    @Test
    void pricesRoundHalfUpToTwoDecimalsAndADiscountThatRoundsAwayIsNotShown() throws Exception {
        priced(doro, "10.05", 0);
        priced(tibs, "0.01", 1);
        save(mains, 50, null, null, List.of(), null, null, false);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(DORO + ".specialPrice").value(5.03)) // 5.025 → 5.03
                .andExpect(jsonPath(TIBS + ".specialPrice").doesNotExist()); // 0.005 → 0.01, the regular price
    }

    @Test
    void anApplicableDishSpecialWinsAndTheyNeverStack() throws Exception {
        active(20);
        Special happyHour = dishSpecial(doro, "350.00", false);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(DORO + ".specialPrice").value(350.0)) // not 336, and not 280 (350 − 20 %)
                .andExpect(jsonPath(DORO + ".specialType").value("DISH"))
                .andExpect(jsonPath(DORO + ".specialPercent").doesNotExist())
                .andExpect(jsonPath(DORO + ".specialNote").value("Happy hour"))
                .andExpect(jsonPath(TIBS + ".specialPrice").value(266.66))
                .andExpect(jsonPath(TIBS + ".specialType").value("CATEGORY"));

        // the dish special stops applying (paused, or no longer below the price): the category discount takes over
        happyHour.setPaused(true);
        specials.save(happyHour);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(DORO + ".specialPrice").value(336.0)).andExpect(jsonPath(DORO + ".specialType").value("CATEGORY"));
        happyHour.setPaused(false);
        happyHour.setSpecialPrice(new BigDecimal("500.00"));
        specials.save(happyHour);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(DORO + ".specialPrice").value(336.0)).andExpect(jsonPath(DORO + ".specialType").value("CATEGORY"));
    }

    @Test
    void scheduledEndedPausedAndOutOfWindowCategorySpecialsNeverReachGuests() throws Exception {
        for (Runnable setup : List.<Runnable>of(
                () -> save(mains, 20, "2026-11-07", null, List.of(), null, null, false),   // starts tomorrow
                () -> save(mains, 20, null, "2026-11-05", List.of(), null, null, false),   // ended yesterday
                () -> save(mains, 20, null, null, List.of(), null, null, true),            // paused
                () -> save(mains, 20, null, null, List.of("MONDAY"), null, null, false),   // not today
                () -> save(mains, 20, null, null, List.of(), "11:00", "14:00", false))) {  // outside the window
            categorySpecials.deleteAll();
            setup.run();
            assertThat(guestMenu()).contains("Doro Wat").doesNotContain("specialPrice").doesNotContain("\"special\"")
                    .doesNotContain("Sweet hour").doesNotContain("ጣፋጭ ሰዓት").doesNotContain("specialPercent");
            assertThat(mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), doro.getId())).andReturn().getResponse().getContentAsString())
                    .doesNotContain("specialPrice");
        }
    }

    @Test
    void theDiscountFollowsTheDishsCurrentPriceAndCategory() throws Exception {
        active(20);
        priced(doro, "500.00", 0);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(jsonPath(DORO + ".specialPrice").value(400.0));

        Category drinks = data.category(menu, "Drinks");
        doro.setCategory(drinks);
        items.save(doro);
        String body = guestMenu();
        assertThat(body).contains("\"specialPrice\":266.66"); // Tibs, still in Mains
        assertThat(body).doesNotContain("\"specialPrice\":400"); // Doro left the discounted category
    }

    @Test
    void freeDishesAreNotDiscountedAndACategoryWithNothingDiscountedGetsNoBadge() throws Exception {
        priced(doro, "0.00", 0);
        priced(tibs, "0.01", 1); // 1 % of 0.01 rounds back to 0.01: no discount
        save(mains, 1, null, null, List.of(), null, null, false);
        assertThat(guestMenu()).doesNotContain("specialPrice").doesNotContain("\"special\"").doesNotContain("Sweet hour");

        // every eligible dish already has its own special: nothing is discounted by the category, so no badge either
        categorySpecials.deleteAll();
        priced(doro, "420.00", 0);
        priced(tibs, "333.33", 1);
        active(20);
        dishSpecial(doro, "350.00", false);
        dishSpecial(tibs, "300.00", false);
        assertThat(guestMenu()).doesNotContain("\"special\"").doesNotContain("Sweet hour").contains("Happy hour");
    }

    @Test
    void categorySpecialsOfHiddenContentNeverReachGuests() throws Exception {
        active(20);
        tibs.setStatus(MenuItemStatus.UNAVAILABLE);
        items.save(tibs);
        assertThat(guestMenu()).doesNotContain("266.66");
        mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), tibs.getId())).andExpect(status().isNotFound());

        mains.setActive(false);
        categories.save(mains);
        assertThat(guestMenu()).doesNotContain("Sweet hour");

        mains.setActive(true);
        categories.save(mains);
        menu.setVisibility(MenuVisibility.PRIVATE);
        menus.save(menu);
        assertThat(guestMenu()).doesNotContain("Sweet hour");
    }

    @Test
    void aSoldOutDishInADiscountedCategoryShowsItsDiscountedPrice() throws Exception {
        active(20);
        doro.setStatus(MenuItemStatus.OUT_OF_STOCK);
        items.save(doro);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath(DORO + ".status").value("OUT_OF_STOCK"))
                .andExpect(jsonPath(DORO + ".specialPrice").value(336.0));
    }

    @Test
    void categorySpecialsAddOneFixedQueryToTheGuestMenu() throws Exception {
        for (int n = 0; n < 3; n++) {
            Category c = data.category(menu, "Extra " + n);
            priced(data.item(c, "Dish " + n), "100.00", 0);
            save(c, 10, null, null, List.of(), null, null, false);
        }
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            assertThat(guestMenu()).contains("\"specialPrice\":90");
            long withThree = stats.getPrepareStatementCount();
            categorySpecials.deleteAll();
            stats.clear();
            guestMenu();
            assertThat(withThree).isEqualTo(stats.getPrepareStatementCount()); // same count with 0 or 3 category specials
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }
}
