package com.agafari.com.controller;

import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S1-15 / PO-2: new menus start unpublished; publish, private and hidden-category transitions as guests see them. */
class MenuPublishingTest extends AbstractIntegrationTest {

    private String create(Tenant owner, String body) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void patchMenu(Tenant owner, String menuId, String body) throws Exception {
        mockMvc.perform(patch("/api/menus/{id}", menuId).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void newMenuWithoutAStateIsCreatedUnpublished() throws Exception {
        Tenant owner = data.tenant("alpha");
        String typeId = data.menuType("Lunch").getId();

        mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Draft Menu\",\"menuTypeId\":\"" + typeId + "\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void explicitIsActiveIsRespectedOnCreate() throws Exception {
        Tenant owner = data.tenant("alpha");
        String typeId = data.menuType("Lunch").getId();

        mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Live Now\",\"menuTypeId\":\"" + typeId + "\",\"isActive\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Not Yet\",\"menuTypeId\":\"" + typeId + "\",\"isActive\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void existingMenusKeepTheirStoredState() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.menu(owner, "Existing Live");                                        // active, PUBLIC
        data.menu(owner, "Existing Private", true, MenuVisibility.PRIVATE);
        data.menu(owner, "Existing Draft", false, MenuVisibility.PUBLIC);

        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$[?(@.name=='Existing Live')].active").value(true))
                .andExpect(jsonPath("$[?(@.name=='Existing Private')].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$[?(@.name=='Existing Draft')].active").value(false));
    }

    @Test
    void unpublishedMenuAppearsForGuestsOnlyAfterPublishing() throws Exception {
        Tenant owner = data.tenant("alpha");
        String menuId = create(owner, "{\"name\":\"Dinner\",\"menuTypeId\":\"" + data.menuType("Dinner").getId() + "\"}");
        String categoryId = JsonPath.read(mockMvc.perform(post("/api/menus/{id}/categories", menuId)
                        .header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mains\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/menus/{id}/items", menuId).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"name\":\"Tibs\",\"price\":10,\"ingredients\":[\"beef\"]}"))
                .andExpect(status().isCreated());

        // Unpublished: not on the guest menu
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.menus", hasSize(0)));

        // Publish -> live for guests
        patchMenu(owner, menuId, "{\"isActive\":true}");
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.menus", hasSize(1)))
                .andExpect(content().string(containsString("Tibs")));

        // Private -> gone for guests, still listed for the owner
        patchMenu(owner, menuId, "{\"visibility\":\"PRIVATE\"}");
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.menus", hasSize(0)));
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$[0].active").value(true));

        // Unpublish while public -> gone again
        patchMenu(owner, menuId, "{\"visibility\":\"PUBLIC\",\"isActive\":false}");
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.menus", hasSize(0)));
    }

    @Test
    void hiddenCategoryIsAbsentForGuestsButStillShownToTheOwner() throws Exception {
        Tenant owner = data.tenant("alpha");
        var menu = data.menu(owner, "All Day");
        var visible = data.category(menu, "Drinks");
        var hidden = data.category(menu, "Specials");
        data.item(visible, "Coffee");
        data.item(hidden, "Secret Dish");

        mockMvc.perform(patch("/api/categories/{id}", hidden.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(content().string(containsString("Coffee")))
                .andExpect(content().string(not(containsString("Specials"))))
                .andExpect(content().string(not(containsString("Secret Dish"))));

        mockMvc.perform(get("/api/menus/{id}/categories", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/menus/{id}/items", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(content().string(containsString("Secret Dish")));
    }
}
