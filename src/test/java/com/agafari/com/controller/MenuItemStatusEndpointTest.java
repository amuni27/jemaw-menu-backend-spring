package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-12: the item status endpoint contract is PUT /api/items/{id}/status (unchanged).
 * The frontend now calls it with PUT; these tests guard the contract it relies on.
 */
class MenuItemStatusEndpointTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @EnumSource(MenuItemStatus.class)
    void putSetsAndPersistsEveryStatus(MenuItemStatus target) throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(put("/api/items/{id}/status", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + target + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(target.name()));

        mockMvc.perform(get("/api/items/{id}", item.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(target.name()));
    }

    @Test
    void hideThenShowControlsGuestVisibility() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(put("/api/items/{id}/status", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Doro Wat"))));

        mockMvc.perform(put("/api/items/{id}/status", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"AVAILABLE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Doro Wat")));
    }

    @Test
    void ownerCannotChangeAnotherTenantsItemStatus() throws Exception {
        var a = data.tenant("alpha");
        var b = data.tenant("bravo");
        var bItem = data.item(data.category(data.menu(b, "Lunch"), "Mains"), "Pasta");

        mockMvc.perform(put("/api/items/{id}/status", bItem.getId())
                        .header("Authorization", data.bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/items/{id}", bItem.getId()).header("Authorization", data.bearer(b)))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    /** PATCH is not mapped on the status endpoint: 405 Method Not Allowed (AGF-S1-10; completes S1-12 AC7). */
    @Test
    void patchIsNotSupportedOnStatusEndpoint() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(patch("/api/items/{id}/status", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isMethodNotAllowed());
    }
}
