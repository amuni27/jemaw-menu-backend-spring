package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-19: POST /api/menus/{menuId}/items/mark-all-available sets every OUT_OF_STOCK item of one menu to AVAILABLE
 * in one transaction and returns how many rows actually changed. Hidden and available items, other menus and other
 * restaurants are untouched.
 */
class MarkAllAvailableTest extends AbstractIntegrationTest {

    @Autowired private MenuItemRepository items;

    private Tenant owner;
    private Menu lunch;
    private final List<MenuItem> soldOut = new ArrayList<>();
    private MenuItem available;
    private MenuItem hidden;

    @BeforeEach
    void menuWithMixedStatuses() {
        owner = data.tenant("alpha");
        lunch = data.menu(owner, "Lunch");
        var mains = data.category(lunch, "Mains");
        for (int i = 1; i <= 5; i++) soldOut.add(data.item(mains, "Sold Out " + i, MenuItemStatus.OUT_OF_STOCK));
        available = data.item(mains, "Doro Wat", MenuItemStatus.AVAILABLE);
        hidden = data.item(mains, "Secret Special", MenuItemStatus.UNAVAILABLE);
    }

    private ResultActions markAll(Menu menu, Tenant who) throws Exception {
        var req = post("/api/menus/{id}/items/mark-all-available", menu.getId()).contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(who == null ? req : req.header("Authorization", data.bearer(who)));
    }

    private MenuItemStatus statusOf(MenuItem i) {
        return items.findById(i.getId()).orElseThrow().getStatus();
    }

    @Test
    void allFiveSoldOutItemsBecomeAvailable() throws Exception {
        markAll(lunch, owner).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(5));
        soldOut.forEach(i -> assertThat(statusOf(i)).isEqualTo(MenuItemStatus.AVAILABLE));
    }

    @Test
    void availableAndHiddenItemsKeepTheirStatus() throws Exception {
        markAll(lunch, owner).andExpect(status().isOk());
        assertThat(statusOf(available)).isEqualTo(MenuItemStatus.AVAILABLE);
        assertThat(statusOf(hidden)).isEqualTo(MenuItemStatus.UNAVAILABLE);
    }

    @Test
    void itemsInOtherMenusAreUnchanged() throws Exception {
        var dinnerItem = data.item(data.category(data.menu(owner, "Dinner"), "Mains"), "Dinner Sold Out", MenuItemStatus.OUT_OF_STOCK);
        markAll(lunch, owner).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(5));
        assertThat(statusOf(dinnerItem)).isEqualTo(MenuItemStatus.OUT_OF_STOCK);
    }

    @Test
    void anotherRestaurantGetsNotFoundAndNothingChanges() throws Exception {
        var other = data.tenant("beta");
        markAll(lunch, other)
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Sold Out"))));
        soldOut.forEach(i -> assertThat(statusOf(i)).isEqualTo(MenuItemStatus.OUT_OF_STOCK));
    }

    @Test
    void withoutATokenTheRequestIsRejected() throws Exception {
        markAll(lunch, null).andExpect(status().isUnauthorized());
        soldOut.forEach(i -> assertThat(statusOf(i)).isEqualTo(MenuItemStatus.OUT_OF_STOCK));
    }

    @Test
    void runningItAgainUpdatesNothing() throws Exception {
        markAll(lunch, owner).andExpect(jsonPath("$.updated").value(5));
        markAll(lunch, owner).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    void theCountIsWhatActuallyChangedEvenIfItemsChangedInBetween() throws Exception {
        // the owner saw 5 sold-out items, but two were changed (e.g. on another device) before confirming
        MenuItem a = soldOut.get(0), b = soldOut.get(1);
        a.setStatus(MenuItemStatus.AVAILABLE);
        b.setStatus(MenuItemStatus.UNAVAILABLE);
        items.save(a);
        items.save(b);

        markAll(lunch, owner).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(3));
        assertThat(statusOf(b)).isEqualTo(MenuItemStatus.UNAVAILABLE);
    }

    @Test
    void guestsSeeTheItemsAsAvailableAndHiddenItemsStayHidden() throws Exception {
        markAll(lunch, owner).andExpect(status().isOk());
        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[0].items", hasSize(6)))
                .andExpect(jsonPath("$.menus[0].categories[0].items[?(@.status == 'OUT_OF_STOCK')]", hasSize(0)))
                .andExpect(content().string(not(containsString("Secret Special"))));
    }

    @Test
    void theSingleItemStatusEndpointStillWorks() throws Exception {
        markAll(lunch, owner).andExpect(status().isOk());
        mockMvc.perform(put("/api/items/{id}/status", available.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OUT_OF_STOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_STOCK"));
        assertThat(statusOf(available)).isEqualTo(MenuItemStatus.OUT_OF_STOCK);
    }
}
