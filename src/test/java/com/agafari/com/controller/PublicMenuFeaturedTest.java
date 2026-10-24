package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-15: the guest "Chef's picks" section is built in the browser from the public menu, so the public API must
 * expose the featured flag (as {@code featured}) and must never send a featured item that guests may not see.
 */
class PublicMenuFeaturedTest extends AbstractIntegrationTest {

    @Autowired private MenuItemRepository items;

    private MenuItem featured(MenuItem item) {
        item.setFeatured(true);
        return items.save(item);
    }

    @Test
    void theFeaturedFlagIsExposedAsFeatured() throws Exception {
        var owner = data.tenant("alpha");
        var mains = data.category(data.menu(owner, "Lunch"), "Mains");
        featured(data.item(mains, "Doro Wat"));
        data.item(mains, "Shiro");

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[0].items[?(@.name == 'Doro Wat')].featured").value(true))
                .andExpect(jsonPath("$.menus[0].categories[0].items[?(@.name == 'Shiro')].featured").value(false));
    }

    @Test
    void aFeaturedSoldOutItemIsSentWithItsStatus() throws Exception {
        var owner = data.tenant("alpha");
        featured(data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Tibs", MenuItemStatus.OUT_OF_STOCK));

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].featured").value(true))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].status").value("OUT_OF_STOCK"));
    }

    @Test
    void aHiddenFeaturedItemIsNeverSent() throws Exception {
        var owner = data.tenant("alpha");
        var mains = data.category(data.menu(owner, "Lunch"), "Mains");
        data.item(mains, "Doro Wat");
        var hidden = featured(data.item(mains, "Secret Special", MenuItemStatus.UNAVAILABLE));

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[0].items", hasSize(1)))
                .andExpect(content().string(not(containsString("Secret Special"))));
        mockMvc.perform(get("/venue/{businessId}/items/{itemId}", owner.businessId(), hidden.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFeaturedItemInAnInactiveCategoryIsNeverSent() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");
        data.item(data.category(menu, "Mains"), "Doro Wat");
        featured(data.item(data.category(menu, "Hidden Category", false), "Hidden Category Pick"));

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories", hasSize(1)))
                .andExpect(content().string(not(containsString("Hidden Category Pick"))));
    }

    @Test
    void featuredItemsOnPrivateOrUnpublishedMenusAreNeverSent() throws Exception {
        var owner = data.tenant("alpha");
        data.item(data.category(data.menu(owner, "Guest Menu"), "Mains"), "Doro Wat");
        featured(data.item(data.category(data.menu(owner, "Staff Menu", true, MenuVisibility.PRIVATE), "Staff"), "Staff Pick"));
        featured(data.item(data.category(data.menu(owner, "Draft Menu", false, MenuVisibility.PUBLIC), "Draft"), "Draft Pick"));

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(1)))
                .andExpect(jsonPath("$.menus[0].name").value("Guest Menu"))
                .andExpect(content().string(not(containsString("Staff Pick"))))
                .andExpect(content().string(not(containsString("Draft Pick"))));
    }
}
