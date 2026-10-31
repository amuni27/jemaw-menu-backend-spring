package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-14: guest dietary filters run in the browser over the public menu, so the public API must keep sending each
 * item's tags and allergens as stored, and must never send a hidden item because of its dietary data.
 */
class PublicMenuDietaryTest extends AbstractIntegrationTest {

    @Autowired private MenuItemRepository items;

    private MenuItem dietary(MenuItem item, List<String> tags, List<String> allergens) {
        item.setTags(tags);
        item.setAllergens(allergens);
        return items.save(item);
    }

    @Test
    void theMenuSendsTagsAndAllergensAsStored() throws Exception {
        var owner = data.tenant("alpha");
        var mains = data.category(data.menu(owner, "Lunch"), "Mains");
        dietary(data.item(mains, "Misir"), List.of("vegan", "Fasting"), List.of());
        dietary(data.item(mains, "Nutty Salad"), List.of("vegan"), List.of("nuts", "sesame"));

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[0].items[?(@.name == 'Misir')].tags[*]", contains("vegan", "Fasting")))
                .andExpect(jsonPath("$.menus[0].categories[0].items[?(@.name == 'Misir')].allergens[*]", empty()))
                .andExpect(jsonPath("$.menus[0].categories[0].items[?(@.name == 'Nutty Salad')].allergens[*]", contains("nuts", "sesame")));
    }

    @Test
    void theItemDetailSendsTagsAndAllergens() throws Exception {
        var owner = data.tenant("alpha");
        var item = dietary(data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Shiro"), List.of("fasting"), List.of("gluten"));

        mockMvc.perform(get("/venue/{businessId}/items/{itemId}", owner.businessId(), item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[*]", contains("fasting")))
                .andExpect(jsonPath("$.allergens[*]", contains("gluten")));
    }

    @Test
    void aHiddenItemWithDietaryTagsIsNeverSent() throws Exception {
        var owner = data.tenant("alpha");
        var mains = data.category(data.menu(owner, "Lunch"), "Mains");
        data.item(mains, "Doro Wat");
        dietary(data.item(mains, "Secret Vegan", MenuItemStatus.UNAVAILABLE), List.of("vegan"), List.of());

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Secret Vegan"))));
    }
}
