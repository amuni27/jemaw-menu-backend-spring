package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-17: GET /api/menus/{menuId}/preview shows the owner one of their own menus exactly as guests would see it once
 * published (guest rules for categories and items; the menu's publish state and visibility are not required), and
 * never makes unpublished or private content visible through the public endpoints.
 */
class MenuPreviewTest extends AbstractIntegrationTest {

    @Autowired private CategoryRepository categories;
    @Autowired private MenuItemRepository items;

    private Category ordered(Category c, int position) {
        c.setSortOrder(position);
        return categories.save(c);
    }

    private MenuItem ordered(MenuItem i, int position) {
        i.setSortOrder(position);
        return items.save(i);
    }

    @ParameterizedTest(name = "active={0} visibility={1}")
    @CsvSource({"false, PUBLIC", "true, PRIVATE", "false, PRIVATE", "true, PUBLIC"})
    void theOwnerPreviewsTheirMenuWhateverItsPublishState(boolean active, MenuVisibility visibility) throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Draft Menu", active, visibility);
        data.item(data.category(menu, "Mains"), "Doro Wat");

        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value(owner.business().getName()))
                .andExpect(jsonPath("$.menus", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].id").value(menu.getId()))
                .andExpect(jsonPath("$.menus[0].active").value(active))
                .andExpect(jsonPath("$.menus[0].visibility").value(visibility.name()))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].name").value("Doro Wat"));
    }

    @Test
    void guestRulesStillApplyToCategoriesAndItems() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Draft Menu", false, MenuVisibility.PUBLIC);
        var mains = data.category(menu, "Mains");
        data.item(mains, "Doro Wat");
        data.item(mains, "Tibs", MenuItemStatus.OUT_OF_STOCK);
        data.item(mains, "Secret Special", MenuItemStatus.UNAVAILABLE);
        data.item(data.category(menu, "Hidden Category", false), "Hidden Category Dish");

        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].categories[0].items[*].name", contains("Doro Wat", "Tibs")))
                .andExpect(jsonPath("$.menus[0].categories[0].items[1].status").value("OUT_OF_STOCK"))
                .andExpect(content().string(not(containsString("Secret Special"))))
                .andExpect(content().string(not(containsString("Hidden Category"))));
    }

    @Test
    void aMenuWithNothingGuestVisibleIsReturnedWithNoCategories() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Empty Draft", false, MenuVisibility.PUBLIC);
        data.item(data.category(menu, "Mains"), "Secret Special", MenuItemStatus.UNAVAILABLE);

        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].name").value("Empty Draft"))
                .andExpect(jsonPath("$.menus[0].categories", empty()));
    }

    @Test
    void thePreviewKeepsTheOwnersSavedOrder() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Draft Menu", false, MenuVisibility.PUBLIC);
        var drinks = ordered(data.category(menu, "Drinks"), 1);
        var mains = ordered(data.category(menu, "Mains"), 0);
        ordered(data.item(mains, "Tibs"), 1);
        ordered(data.item(mains, "Doro Wat"), 0);
        data.item(drinks, "Tea");

        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[*].name", contains("Mains", "Drinks")))
                .andExpect(jsonPath("$.menus[0].categories[0].items[*].name", contains("Doro Wat", "Tibs")));
    }

    @Test
    void withoutATokenThePreviewIsRefused() throws Exception {
        var owner = data.tenant("alpha");
        Menu menu = data.menu(owner, "Draft Menu", false, MenuVisibility.PUBLIC);

        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("Draft Menu"))));
    }

    @Test
    void anotherRestaurantGetsNotFoundAndNoData() throws Exception {
        var owner = data.tenant("alpha");
        var other = data.tenant("beta");
        var menu = data.menu(owner, "Alpha Draft", false, MenuVisibility.PRIVATE);
        data.item(data.category(menu, "Mains"), "Alpha Secret Dish");

        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Alpha Secret Dish"))))
                .andExpect(content().string(not(containsString("Alpha Draft"))));
    }

    @Test
    void anUnknownMenuIsNotFound() throws Exception {
        var owner = data.tenant("alpha");

        mockMvc.perform(get("/api/menus/{id}/preview", "00000000-0000-0000-0000-000000000000").header("Authorization", data.bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicEndpointsStillHideUnpublishedAndPrivateMenusAfterAPreview() throws Exception {
        var owner = data.tenant("alpha");
        var draft = data.menu(owner, "Draft Menu", false, MenuVisibility.PUBLIC);
        var staff = data.menu(owner, "Staff Menu", true, MenuVisibility.PRIVATE);
        var draftItem = data.item(data.category(draft, "Mains"), "Draft Dish");
        var staffItem = data.item(data.category(staff, "Staff"), "Staff Dish");
        data.item(data.category(data.menu(owner, "Guest Menu"), "Mains"), "Doro Wat");

        for (Menu m : new Menu[]{draft, staff})
            mockMvc.perform(get("/api/menus/{id}/preview", m.getId()).header("Authorization", data.bearer(owner)))
                    .andExpect(status().isOk());

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].name").value("Guest Menu"))
                .andExpect(content().string(not(containsString("Draft Dish"))))
                .andExpect(content().string(not(containsString("Staff Dish"))));
        for (MenuItem i : new MenuItem[]{draftItem, staffItem})
            mockMvc.perform(get("/venue/{businessId}/items/{itemId}", owner.businessId(), i.getId()))
                    .andExpect(status().isNotFound());
    }
}
