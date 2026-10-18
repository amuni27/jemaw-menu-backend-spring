package com.agafari.com.controller;

import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S1-14: menu create/update API defects (menu type change, description clearing, unchanged fields). */
class MenuUpdateTest extends AbstractIntegrationTest {

    @Autowired private MenuRepository menus;

    @Test
    void changingTheMenuTypeIsSavedAndShownInTheList() throws Exception {
        Tenant owner = data.tenant("alpha");
        var menu = data.menu(owner, "All Day"); // type Lunch
        var dinner = data.menuType("Dinner");

        mockMvc.perform(patch("/api/menus/{id}", menu.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuTypeId\":\"" + dinner.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuTypeId").value(dinner.getId()))
                .andExpect(jsonPath("$.menuTypeName").value("Dinner"))
                .andExpect(jsonPath("$.name").value("All Day"));

        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$[0].menuTypeName").value("Dinner"));
    }

    @Test
    void unknownMenuTypeIsRejectedWith400AndNothingChanges() throws Exception {
        Tenant owner = data.tenant("alpha");
        var menu = data.menu(owner, "All Day");
        String typeBefore = menu.getMenuType().getId();

        for (String bad : new String[]{"no-such-type", ""}) {
            mockMvc.perform(patch("/api/menus/{id}", menu.getId()).header("Authorization", data.bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"menuTypeId\":\"" + bad + "\",\"name\":\"Renamed\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid menu type"));
        }

        var after = menus.findByIdAndBusiness_Id(menu.getId(), owner.businessId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("All Day");
        mockMvc.perform(get("/api/menus/{id}", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.menuTypeId").value(typeBefore));
    }

    @Test
    void emptyDescriptionClearsItAndOmittedDescriptionKeepsIt() throws Exception {
        Tenant owner = data.tenant("alpha");
        var menu = data.menu(owner, "All Day");
        String bearer = data.bearer(owner);

        mockMvc.perform(patch("/api/menus/{id}", menu.getId()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Served all day\"}"))
                .andExpect(jsonPath("$.description").value("Served all day"));

        mockMvc.perform(patch("/api/menus/{id}", menu.getId()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"All Day Menu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Served all day"));

        mockMvc.perform(patch("/api/menus/{id}", menu.getId()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()));

        assertThat(menus.findByIdAndBusiness_Id(menu.getId(), owner.businessId()).orElseThrow().getDescription()).isNull();
    }

    @Test
    void editingOnlyTheNameKeepsVisibilityAndPublishState() throws Exception {
        Tenant owner = data.tenant("alpha");
        var privateMenu = data.menu(owner, "Staff Menu", false, MenuVisibility.PRIVATE);

        mockMvc.perform(patch("/api/menus/{id}", privateMenu.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Staff Meals\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Staff Meals"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void creatingWithoutADescriptionSucceedsAndStoresNoDescription() throws Exception {
        Tenant owner = data.tenant("alpha");
        var lunch = data.menuType("Lunch");

        for (String body : new String[]{
                "{\"name\":\"Lunch\",\"menuTypeId\":\"" + lunch.getId() + "\",\"description\":\"\",\"visibility\":\"PUBLIC\"}",
                "{\"name\":\"Brunch\",\"menuTypeId\":\"" + lunch.getId() + "\"}"}) {
            mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").value(nullValue()))
                    .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                    .andExpect(jsonPath("$.active").value(false)); // AGF-S1-15 / PO-2: new menus start unpublished
        }
    }
}
