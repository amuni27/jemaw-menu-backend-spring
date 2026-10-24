package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S2-08: owners reorder the items of a category; the owner list and the guest menu use the saved order. */
class ItemReorderTest extends AbstractIntegrationTest {

    @Autowired private MenuItemRepository items;

    private Tenant owner;
    private Menu menu;
    private Category mains, drinks;
    private MenuItem x, y, z, tea;

    @BeforeEach
    void setUp() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        mains = data.category(menu, "Mains");
        drinks = data.category(menu, "Drinks");
        x = ordered(data.item(mains, "X Tibs"), 0);
        y = ordered(data.item(mains, "Y Shiro"), 1);
        z = ordered(data.item(mains, "Z Kitfo"), 2);
        tea = ordered(data.item(drinks, "Tea"), 0);
    }

    private MenuItem ordered(MenuItem item, int sortOrder) {
        item.setSortOrder(sortOrder);
        return items.save(item);
    }

    private static String body(String... ids) {
        return "{\"orderedIds\":[" + String.join(",", Arrays.stream(ids).map(i -> "\"" + i + "\"").toList()) + "]}";
    }

    private int reorder(Tenant as, String categoryId, String... ids) throws Exception {
        return mockMvc.perform(post("/api/categories/{id}/items/reorder", categoryId).header("Authorization", data.bearer(as))
                        .contentType(MediaType.APPLICATION_JSON).content(body(ids)))
                .andReturn().getResponse().getStatus();
    }

    private List<String> ownerOrder(Category category) throws Exception {
        String json = mockMvc.perform(get("/api/menus/{id}/items", menu.getId()).param("categoryId", category.getId())
                        .header("Authorization", data.bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[*].name");
    }

    private List<String> guestOrder(String categoryName) throws Exception {
        String json = mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.menus[0].categories[?(@.name == '" + categoryName + "')].items[*].name");
    }

    @Test
    void movingZFirstIsSavedForTheOwnerAndGuests() throws Exception {
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), y.getId())).isEqualTo(200);

        assertThat(ownerOrder(mains)).containsExactly("Z Kitfo", "X Tibs", "Y Shiro");
        assertThat(guestOrder("Mains")).containsExactly("Z Kitfo", "X Tibs", "Y Shiro");
        assertThat(ownerOrder(drinks)).containsExactly("Tea"); // other categories untouched
    }

    @Test
    void anItemFromAnotherCategoryIsRejected() throws Exception {
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), tea.getId())).isEqualTo(400);
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), y.getId(), tea.getId())).isEqualTo(400);
        assertThat(ownerOrder(mains)).containsExactly("X Tibs", "Y Shiro", "Z Kitfo");
    }

    @Test
    void partialDuplicateUnknownAndEmptyListsAreRejected() throws Exception {
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId())).isEqualTo(400);
        assertThat(reorder(owner, mains.getId(), z.getId(), z.getId(), x.getId(), y.getId())).isEqualTo(400);
        assertThat(reorder(owner, mains.getId(), z.getId(), z.getId(), x.getId())).isEqualTo(400);
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), "no-such-item")).isEqualTo(400);
        mockMvc.perform(post("/api/categories/{id}/items/reorder", mains.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("orderedIds")));
        assertThat(ownerOrder(mains)).containsExactly("X Tibs", "Y Shiro", "Z Kitfo");
    }

    @Test
    void anotherOwnersCategoryIsNotFoundAndTheirItemsAreRejected() throws Exception {
        Tenant other = data.tenant("bravo");
        Category otherCat = data.category(data.menu(other, "Bravo Lunch"), "Bravo Mains");
        MenuItem otherItem = data.item(otherCat, "Bravo Pasta");

        // A tries to reorder B's category
        assertThat(reorder(owner, otherCat.getId(), otherItem.getId())).isEqualTo(404);
        // A slips B's item into A's own category
        assertThat(reorder(owner, mains.getId(), otherItem.getId(), x.getId(), y.getId(), z.getId())).isEqualTo(400);
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), otherItem.getId())).isEqualTo(400);

        assertThat(ownerOrder(mains)).containsExactly("X Tibs", "Y Shiro", "Z Kitfo");
        assertThat(items.findById(otherItem.getId()).orElseThrow().getSortOrder()).isZero();
    }

    @Test
    void reorderRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/categories/{id}/items/reorder", mains.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body(z.getId(), x.getId(), y.getId())))
                .andExpect(status().isUnauthorized());
        assertThat(ownerOrder(mains)).containsExactly("X Tibs", "Y Shiro", "Z Kitfo");
    }

    @Test
    void hiddenAndSoldOutItemsTakePartAndKeepTheirStatus() throws Exception {
        y.setStatus(MenuItemStatus.UNAVAILABLE);
        items.save(y);
        z.setStatus(MenuItemStatus.OUT_OF_STOCK);
        items.save(z);

        assertThat(reorder(owner, mains.getId(), y.getId(), z.getId(), x.getId())).isEqualTo(200);

        assertThat(ownerOrder(mains)).containsExactly("Y Shiro", "Z Kitfo", "X Tibs");
        assertThat(guestOrder("Mains")).containsExactly("Z Kitfo", "X Tibs"); // hidden Y stays hidden
        assertThat(items.findById(z.getId()).orElseThrow().getStatus()).isEqualTo(MenuItemStatus.OUT_OF_STOCK);
    }

    @Test
    void aNewItemIsAppendedAtTheEnd() throws Exception {
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), y.getId())).isEqualTo(200);

        mockMvc.perform(post("/api/menus/{id}/items", menu.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + mains.getId() + "\",\"name\":\"W Wat\",\"price\":5,\"ingredients\":[\"x\"]}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(ownerOrder(mains)).containsExactly("Z Kitfo", "X Tibs", "Y Shiro", "W Wat");
        assertThat(guestOrder("Mains")).containsExactly("Z Kitfo", "X Tibs", "Y Shiro", "W Wat");
    }

    @Test
    void anItemMovedToAnotherCategoryGoesLastThere() throws Exception {
        mockMvc.perform(patch("/api/items/{id}", x.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + drinks.getId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(ownerOrder(drinks)).containsExactly("Tea", "X Tibs");
        assertThat(guestOrder("Drinks")).containsExactly("Tea", "X Tibs");
    }

    @Test
    void savingAnItemWithItsOwnCategoryKeepsItsPosition() throws Exception {
        assertThat(reorder(owner, mains.getId(), z.getId(), x.getId(), y.getId())).isEqualTo(200);

        // the item form always sends categoryId, even when it is unchanged
        mockMvc.perform(patch("/api/items/{id}", z.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + mains.getId() + "\",\"name\":\"Z Kitfo\"}"))
                .andExpect(status().isOk());

        assertThat(ownerOrder(mains)).containsExactly("Z Kitfo", "X Tibs", "Y Shiro");
    }

    @Test
    void ownerAndGuestBreakEqualPositionsTheSameWay() throws Exception {
        // legacy data: several items on the same position (created X, then Y, then Z)
        for (MenuItem item : List.of(x, y, z)) ordered(items.findById(item.getId()).orElseThrow(), 0);

        assertThat(ownerOrder(mains)).containsExactly("X Tibs", "Y Shiro", "Z Kitfo");
        assertThat(guestOrder("Mains")).containsExactly("X Tibs", "Y Shiro", "Z Kitfo");
    }
}
