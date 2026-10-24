package com.agafari.com.controller;

import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S2-07: owners reorder a menu's categories; the owner list and the guest menu use the saved order. */
class CategoryReorderTest extends AbstractIntegrationTest {

    @Autowired private CategoryRepository categories;

    private Tenant owner;
    private Menu menu;
    private Category a, b, c;

    @BeforeEach
    void setUp() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        a = ordered(data.category(menu, "A Starters"), 0);
        b = ordered(data.category(menu, "B Mains"), 1);
        c = ordered(data.category(menu, "C Drinks"), 2);
        // guest menus only list categories that have items
        for (Category cat : List.of(a, b, c)) data.item(cat, cat.getName() + " item");
    }

    private Category ordered(Category cat, int sortOrder) {
        cat.setSortOrder(sortOrder);
        return categories.save(cat);
    }

    private int reorder(String... ids) throws Exception {
        String body = "{\"orderedIds\":[" + String.join(",", java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").toList()) + "]}";
        return mockMvc.perform(post("/api/menus/{id}/categories/reorder", menu.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private List<String> ownerOrder() throws Exception {
        String json = mockMvc.perform(get("/api/menus/{id}/categories", menu.getId()).header("Authorization", data.bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[*].name");
    }

    private List<String> guestOrder() throws Exception {
        String json = mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.menus[0].categories[*].name");
    }

    @Test
    void movingCToTheTopIsSavedForTheOwnerAndGuests() throws Exception {
        assertThat(reorder(c.getId(), a.getId(), b.getId())).isEqualTo(200);

        assertThat(ownerOrder()).containsExactly("C Drinks", "A Starters", "B Mains");
        assertThat(guestOrder()).containsExactly("C Drinks", "A Starters", "B Mains");
    }

    @Test
    void hiddenCategoriesCanBeReorderedButStayHiddenFromGuests() throws Exception {
        mockMvc.perform(patch("/api/categories/{id}", b.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isOk());

        assertThat(reorder(b.getId(), c.getId(), a.getId())).isEqualTo(200);

        assertThat(ownerOrder()).containsExactly("B Mains", "C Drinks", "A Starters");
        assertThat(guestOrder()).containsExactly("C Drinks", "A Starters");
        mockMvc.perform(get("/api/menus/{id}/categories", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void aPartialListIsRejectedAndTheOrderIsKept() throws Exception {
        assertThat(reorder(c.getId())).isEqualTo(400);
        assertThat(ownerOrder()).containsExactly("A Starters", "B Mains", "C Drinks");
    }

    @Test
    void duplicateIdsAreRejected() throws Exception {
        assertThat(reorder(c.getId(), c.getId(), a.getId(), b.getId())).isEqualTo(400);
        assertThat(reorder(c.getId(), c.getId(), a.getId())).isEqualTo(400);
        assertThat(ownerOrder()).containsExactly("A Starters", "B Mains", "C Drinks");
    }

    @Test
    void unknownIdsAreRejected() throws Exception {
        assertThat(reorder(c.getId(), a.getId(), "no-such-category")).isEqualTo(400);
        assertThat(ownerOrder()).containsExactly("A Starters", "B Mains", "C Drinks");
    }

    @Test
    void aSingleCategoryMenuCanBeSaved() throws Exception {
        Menu single = data.menu(owner, "Drinks only");
        Category only = data.category(single, "Only");
        mockMvc.perform(post("/api/menus/{id}/categories/reorder", single.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[\"" + only.getId() + "\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void aNewCategoryIsAddedAfterTheSavedOrder() throws Exception {
        assertThat(reorder(c.getId(), a.getId(), b.getId())).isEqualTo(200);

        String created = mockMvc.perform(post("/api/menus/{id}/categories", menu.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"D Desserts\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        data.item(categories.findById(JsonPath.read(created, "$.id")).orElseThrow(), "Cake");

        assertThat(ownerOrder()).containsExactly("C Drinks", "A Starters", "B Mains", "D Desserts");
        assertThat(guestOrder()).containsExactly("C Drinks", "A Starters", "B Mains", "D Desserts");
    }
}
