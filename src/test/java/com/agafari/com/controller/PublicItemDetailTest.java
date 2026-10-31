package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-05: public item detail follows the same visibility rule as the guest menu
 * (PublicVisibilityPolicy) and, on the tenant-scoped endpoint, belongs to the requested business.
 */
class PublicItemDetailTest extends AbstractIntegrationTest {

    private static final String SCOPED = "/venue/{businessId}/items/{itemId}";
    private static final String LEGACY = "/venue/items/{itemId}";

    @ParameterizedTest(name = "scoped: menuActive={0} visibility={1} categoryActive={2} status={3} -> visible={4}")
    @MethodSource("com.agafari.com.controller.PublicMenuVisibilityTest#visibilityMatrix")
    void scopedEndpointFollowsVisibilityRule(boolean menuActive, MenuVisibility visibility, boolean categoryActive,
                                             MenuItemStatus itemStatus, boolean expectedVisible) throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "M", menuActive, visibility), "C", categoryActive), "Dish", itemStatus);

        var result = mockMvc.perform(get(SCOPED, owner.businessId(), item.getId()));
        if (expectedVisible) {
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(item.getId()))
                    .andExpect(jsonPath("$.status").value(itemStatus.name()));
        } else {
            result.andExpect(status().isNotFound());
        }
    }

    @ParameterizedTest(name = "legacy: menuActive={0} visibility={1} categoryActive={2} status={3} -> visible={4}")
    @MethodSource("com.agafari.com.controller.PublicMenuVisibilityTest#visibilityMatrix")
    void legacyEndpointFollowsVisibilityRule(boolean menuActive, MenuVisibility visibility, boolean categoryActive,
                                             MenuItemStatus itemStatus, boolean expectedVisible) throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "M", menuActive, visibility), "C", categoryActive), "Dish", itemStatus);

        mockMvc.perform(get(LEGACY, item.getId()))
                .andExpect(expectedVisible ? status().isOk() : status().isNotFound());
    }

    @Test
    void visibleItemIsReturnedWithPublicFieldsOnly() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{
                get(SCOPED, owner.businessId(), item.getId()), get(LEGACY, item.getId())}) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(item.getId()))
                    .andExpect(jsonPath("$.name").value("Doro Wat"))
                    .andExpect(jsonPath("$.price").value(10.0))
                    .andExpect(jsonPath("$.status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.ingredients[0]").value("ingredient"))
                    .andExpect(jsonPath("$.menuId").doesNotExist())
                    .andExpect(jsonPath("$.categoryId").doesNotExist())
                    .andExpect(jsonPath("$.createdAt").doesNotExist())
                    .andExpect(jsonPath("$.updatedAt").doesNotExist());
        }
    }

    @Test
    void scopedEndpointRejectsAnotherTenantsItem() throws Exception {
        var a = data.tenant("alpha");
        var b = data.tenant("bravo");
        var bItem = data.item(data.category(data.menu(b, "Lunch"), "Mains"), "Bravo Dish");

        mockMvc.perform(get(SCOPED, a.businessId(), bItem.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get(SCOPED, b.businessId(), bItem.getId())).andExpect(status().isOk());
    }

    @Test
    void unknownItemOrBusinessReturns404() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");
        String unknown = "00000000-0000-0000-0000-000000000000";

        mockMvc.perform(get(SCOPED, owner.businessId(), unknown)).andExpect(status().isNotFound());
        mockMvc.perform(get(SCOPED, unknown, item.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get(LEGACY, unknown)).andExpect(status().isNotFound());
    }

    /** A hidden item must be indistinguishable from a non-existent one. */
    @Test
    void hiddenAndNonexistentItemsReturnIdenticalResponses() throws Exception {
        var owner = data.tenant("alpha");
        var hidden = data.item(data.category(data.menu(owner, "Staff", true, MenuVisibility.PRIVATE), "Staff"), "Staff Meal");
        String unknown = "00000000-0000-0000-0000-000000000000";

        String hiddenBody = mockMvc.perform(get(SCOPED, owner.businessId(), hidden.getId()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String unknownBody = mockMvc.perform(get(SCOPED, owner.businessId(), unknown))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String legacyHiddenBody = mockMvc.perform(get(LEGACY, hidden.getId()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(hiddenBody).isEqualTo(unknownBody).isEqualTo(legacyHiddenBody).doesNotContain("Staff");
    }

    @Test
    void publicItemDetailNeedsNoAuthentication() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(get(SCOPED, owner.businessId(), item.getId())).andExpect(status().isOk());
    }

    @Test
    void legacyEndpointIsMarkedDeprecatedInApiDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/venue/items/{itemId}'].get.deprecated").value(true))
                .andExpect(jsonPath("$.paths['/venue/{businessId}/items/{itemId}'].get").exists());
    }
}
