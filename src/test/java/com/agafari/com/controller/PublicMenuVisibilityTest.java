package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-04: GET /venue/{businessId}/menu exposes an item only when
 * menu active AND menu PUBLIC AND category active AND item status in {AVAILABLE, OUT_OF_STOCK}.
 */
class PublicMenuVisibilityTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    static Stream<Arguments> visibilityMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean menuActive : new boolean[]{true, false})
            for (MenuVisibility visibility : MenuVisibility.values())
                for (boolean categoryActive : new boolean[]{true, false})
                    for (MenuItemStatus status : MenuItemStatus.values()) {
                        boolean visible = menuActive && visibility == MenuVisibility.PUBLIC && categoryActive
                                && (status == MenuItemStatus.AVAILABLE || status == MenuItemStatus.OUT_OF_STOCK);
                        cases.add(Arguments.of(menuActive, visibility, categoryActive, status, visible));
                    }
        return cases.stream(); // 2 x 2 x 2 x 3 = 24 combinations
    }

    @ParameterizedTest(name = "menuActive={0} visibility={1} categoryActive={2} status={3} -> visible={4}")
    @MethodSource("visibilityMatrix")
    void exposesItemOnlyWhenEveryRuleAllows(boolean menuActive, MenuVisibility visibility, boolean categoryActive,
                                            MenuItemStatus itemStatus, boolean expectedVisible) throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Matrix Menu", menuActive, visibility);
        data.item(data.category(menu, "Matrix Category", categoryActive), "Matrix Item", itemStatus);

        var result = mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk());

        if (expectedVisible) {
            result.andExpect(jsonPath("$.menus", hasSize(1)))
                    .andExpect(jsonPath("$.menus[0].categories[0].items[0].name").value("Matrix Item"))
                    .andExpect(jsonPath("$.menus[0].categories[0].items[0].status").value(itemStatus.name()));
        } else {
            result.andExpect(jsonPath("$.menus", hasSize(0)))
                    .andExpect(content().string(not(containsString("Matrix Item"))));
        }
    }

    @Test
    void privateMenuIsHiddenWhilePublicMenuOfSameBusinessIsShown() throws Exception {
        var owner = data.tenant("alpha");
        data.item(data.category(data.menu(owner, "Staff Menu", true, MenuVisibility.PRIVATE), "Staff"), "Staff Meal");
        data.item(data.category(data.menu(owner, "Guest Menu"), "Mains"), "Doro Wat");

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].name").value("Guest Menu"))
                .andExpect(content().string(not(containsString("Staff Meal"))))
                .andExpect(content().string(not(containsString("Staff Menu"))));
    }

    @Test
    void categoryWithOnlyHiddenItemsIsOmittedButOtherCategoriesRemain() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");
        data.item(data.category(menu, "Hidden Only"), "Gone", MenuItemStatus.UNAVAILABLE);
        data.item(data.category(menu, "Mains"), "Doro Wat");

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].categories[0].name").value("Mains"))
                .andExpect(content().string(not(containsString("Hidden Only"))));
    }

    @Test
    void menuWhoseItemsAreAllHiddenIsOmitted() throws Exception {
        var owner = data.tenant("alpha");
        data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Gone", MenuItemStatus.UNAVAILABLE);

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.id").value(owner.businessId()))
                .andExpect(jsonPath("$.menus", hasSize(0)));
    }

    @Test
    void anotherTenantsContentNeverAppears() throws Exception {
        var a = data.tenant("alpha");
        var b = data.tenant("bravo");
        data.item(data.category(data.menu(a, "Alpha Lunch"), "Mains"), "Alpha Dish");
        data.item(data.category(data.menu(b, "Bravo Lunch"), "Mains"), "Bravo Dish");
        data.item(data.category(data.menu(b, "Bravo Specials"), "Mains"), "Bravo Sold Out", MenuItemStatus.OUT_OF_STOCK);

        mockMvc.perform(get("/venue/{businessId}/menu", a.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alpha Dish")))
                .andExpect(content().string(not(containsString("Bravo"))));
    }

    @Test
    void unknownBusinessReturns404() throws Exception {
        mockMvc.perform(get("/venue/{businessId}/menu", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void businessWithoutMenusReturnsEmptyList() throws Exception {
        var owner = data.tenant("alpha");

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value("alpha Restaurant"))
                .andExpect(jsonPath("$.menus", hasSize(0)));
    }

    /** API compatibility: same path, same response field names as before S1-04. */
    @Test
    void responseShapeIsUnchanged() throws Exception {
        var owner = data.tenant("alpha");
        data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.id").exists())
                .andExpect(jsonPath("$.business.name").exists())
                .andExpect(jsonPath("$.business.customSubdomain").exists())
                .andExpect(jsonPath("$.business.open24_7").exists())
                .andExpect(jsonPath("$.menus[0].id").exists())
                .andExpect(jsonPath("$.menus[0].name").exists())
                .andExpect(jsonPath("$.menus[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.menus[0].menuType.name").value("Lunch"))
                .andExpect(jsonPath("$.menus[0].categories[0].id").exists())
                .andExpect(jsonPath("$.menus[0].categories[0].menuId").exists())
                .andExpect(jsonPath("$.menus[0].categories[0].sortOrder").exists())
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].id").exists())
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].price").value(10.0))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].ingredients[0]").value("ingredient"));
    }

    /** Performance guard: the public menu is still built with a fixed number of SQL statements. */
    @Test
    void publicMenuUsesAFixedNumberOfQueries() throws Exception {
        var owner = data.tenant("alpha");
        for (int m = 0; m < 2; m++) {
            var menu = data.menu(owner, "Menu " + m);
            for (int c = 0; c < 3; c++) {
                var category = data.category(menu, "Category " + m + c);
                for (int i = 0; i < 4; i++) data.item(category, "Item " + m + c + i);
            }
        }

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menus", hasSize(2)));
            long statements = stats.getPrepareStatementCount();
            System.out.println("AGF-S1-04 public menu SQL statements: " + statements);
            assertThat(statements).isLessThanOrEqualTo(PUBLIC_MENU_QUERY_BUDGET);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    // Measured on the pre-S1-04 code with the same data (see S1-04 report); must not grow with data size
    private static final long PUBLIC_MENU_QUERY_BUDGET = 7; // +1 fixed query each for dish specials (AGF-S2-25) and category specials (AGF-S2-36)
}
