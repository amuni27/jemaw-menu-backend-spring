package com.agafari.com.controller;

import com.agafari.com.enums.SpiceLevel;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-30: owners can clear a saved prep time or spice level with the explicit {@code clearPrepTime} /
 * {@code clearSpiceLevel} flags. A missing or null value still means "unchanged", as before.
 */
class MenuItemClearFieldsTest extends AbstractIntegrationTest {

    @Autowired private MenuItemRepository items;

    private Tenant owner;
    private MenuItem item;

    @BeforeEach
    void savedItemWithPrepTimeAndSpice() {
        owner = data.tenant("alpha");
        item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");
        item.setPrepTimeMinutes(20);
        item.setSpiceLevel(SpiceLevel.HOT);
        item = items.save(item);
    }

    private ResultActions update(Tenant who, String json) throws Exception {
        return mockMvc.perform(patch("/api/items/{id}", item.getId()).header("Authorization", data.bearer(who))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private MenuItem stored() {
        return items.findById(item.getId()).orElseThrow();
    }

    @Test
    void clearingThePrepTimeStoresNullAndKeepsTheSpiceLevel() throws Exception {
        update(owner, "{\"clearPrepTime\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prepTimeMinutes").doesNotExist())
                .andExpect(jsonPath("$.spiceLevel").value("HOT"));
        assertThat(stored().getPrepTimeMinutes()).isNull();
        assertThat(stored().getSpiceLevel()).isEqualTo(SpiceLevel.HOT);

        // a fresh read (the owner reloading the page) still has no prep time
        mockMvc.perform(get("/api/items/{id}", item.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prepTimeMinutes").doesNotExist());
    }

    @Test
    void clearingTheSpiceLevelStoresNullAndKeepsThePrepTime() throws Exception {
        update(owner, "{\"clearSpiceLevel\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spiceLevel").doesNotExist())
                .andExpect(jsonPath("$.prepTimeMinutes").value(20));
        assertThat(stored().getSpiceLevel()).isNull();
        assertThat(stored().getPrepTimeMinutes()).isEqualTo(20);
    }

    @Test
    void bothCanBeClearedTogether() throws Exception {
        update(owner, "{\"clearPrepTime\":true,\"clearSpiceLevel\":true}").andExpect(status().isOk());
        assertThat(stored().getPrepTimeMinutes()).isNull();
        assertThat(stored().getSpiceLevel()).isNull();
    }

    @Test
    void editingOnlyTheNameKeepsPrepTimeAndSpiceLevel() throws Exception {
        update(owner, "{\"name\":\"Doro Wat Special\"}").andExpect(status().isOk());
        assertThat(stored().getName()).isEqualTo("Doro Wat Special");
        assertThat(stored().getPrepTimeMinutes()).isEqualTo(20);
        assertThat(stored().getSpiceLevel()).isEqualTo(SpiceLevel.HOT);
    }

    @Test
    void anExplicitNullStillMeansUnchanged() throws Exception {
        update(owner, "{\"prepTimeMinutes\":null,\"spiceLevel\":null,\"clearPrepTime\":false,\"clearSpiceLevel\":null}")
                .andExpect(status().isOk());
        assertThat(stored().getPrepTimeMinutes()).isEqualTo(20);
        assertThat(stored().getSpiceLevel()).isEqualTo(SpiceLevel.HOT);
    }

    @Test
    void newValuesForBothPersistTogether() throws Exception {
        update(owner, "{\"prepTimeMinutes\":35,\"spiceLevel\":\"MILD\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prepTimeMinutes").value(35))
                .andExpect(jsonPath("$.spiceLevel").value("MILD"));
        assertThat(stored().getPrepTimeMinutes()).isEqualTo(35);
        assertThat(stored().getSpiceLevel()).isEqualTo(SpiceLevel.MILD);
    }

    @Test
    void aValueTogetherWithItsClearFlagIsRejectedAndNothingChanges() throws Exception {
        update(owner, "{\"name\":\"Renamed\",\"prepTimeMinutes\":10,\"clearPrepTime\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send either prepTimeMinutes or clearPrepTime, not both"));
        update(owner, "{\"spiceLevel\":\"MILD\",\"clearSpiceLevel\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send either spiceLevel or clearSpiceLevel, not both"));
        assertThat(stored().getName()).isEqualTo("Doro Wat");
        assertThat(stored().getPrepTimeMinutes()).isEqualTo(20);
        assertThat(stored().getSpiceLevel()).isEqualTo(SpiceLevel.HOT);
    }

    @Test
    void anotherRestaurantCannotClearTheFields() throws Exception {
        var other = data.tenant("beta");
        update(other, "{\"clearPrepTime\":true,\"clearSpiceLevel\":true}").andExpect(status().isNotFound());
        assertThat(stored().getPrepTimeMinutes()).isEqualTo(20);
        assertThat(stored().getSpiceLevel()).isEqualTo(SpiceLevel.HOT);
    }

    @Test
    void guestsSeeNoPrepTimeOrSpiceAfterClearing() throws Exception {
        update(owner, "{\"clearPrepTime\":true,\"clearSpiceLevel\":true}").andExpect(status().isOk());

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].prepTimeMinutes").doesNotExist())
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].spiceLevel").doesNotExist());
        mockMvc.perform(get("/venue/{businessId}/items/{itemId}", owner.businessId(), item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Doro Wat"))
                .andExpect(jsonPath("$.prepTimeMinutes").doesNotExist())
                .andExpect(jsonPath("$.spiceLevel").doesNotExist());
    }
}
