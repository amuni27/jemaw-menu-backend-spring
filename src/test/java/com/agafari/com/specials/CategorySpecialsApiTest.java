package com.agafari.com.specials;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.CategorySpecialRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.service.impl.CategorySpecialServiceImpl;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-36 (checkpoint A): category specials, an additive option next to dish specials (S2-25 unchanged). A
 * percentage off every eligible dish of one category, with the same schedule rules. Owner CRUD with field errors,
 * one per category, tenant isolation (404), deleted with the category, same-restaurant database trigger.
 * Dish specials and their API are unchanged; no category special data reaches guests in checkpoint A.
 */
class CategorySpecialsApiTest extends AbstractIntegrationTest {

    @Autowired private CategorySpecialRepository categorySpecials;
    @Autowired private CategorySpecialServiceImpl service;
    @Autowired private MenuItemRepository items;
    @Autowired private JdbcTemplate jdbc;

    private Tenant owner;
    private Menu menu;
    private Category desserts;
    private MenuItem baklava;

    @BeforeEach
    void category() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        desserts = data.category(menu, "Desserts");
        baklava = data.item(desserts, "Baklava");                         // 10.00, available
        data.item(desserts, "Fruit plate", MenuItemStatus.OUT_OF_STOCK);   // eligible (shown as sold out)
        data.item(desserts, "Secret cake", MenuItemStatus.UNAVAILABLE);    // hidden: not eligible
        MenuItem free = data.item(desserts, "Free mint");
        free.setPrice(BigDecimal.ZERO);
        items.save(free);                                                  // price 0: not eligible
        service.setClock(Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC)); // Wed 12:00 Addis
    }

    @AfterEach
    void systemClock() {
        service.setClock(Clock.systemUTC());
    }

    private ResultActions send(MockHttpServletRequestBuilder req, Tenant who, String json) throws Exception {
        return mockMvc.perform(req.header("Authorization", data.bearer(who)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions create(Tenant who, String fields) throws Exception {
        return send(post("/api/category-specials"), who, "{\"categoryId\":\"" + desserts.getId() + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}");
    }

    private String createdId(String fields) throws Exception {
        return JsonPath.read(create(owner, fields).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static ResultActions fieldError(ResultActions r, String field) throws Exception {
        return r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }

    @Test
    void anOwnerCreatesACategorySpecialAndSeesItListed() throws Exception {
        create(owner, "\"discountPercent\":20,\"note\":\" Sweet hour \",\"noteAm\":\"ጣፋጭ ሰዓት\",\"daysOfWeek\":[\"WEDNESDAY\"],"
                + "\"startTime\":\"11:00\",\"endTime\":\"14:00\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(desserts.getId()))
                .andExpect(jsonPath("$.categoryName").value("Desserts"))
                .andExpect(jsonPath("$.menuName").value("Lunch"))
                .andExpect(jsonPath("$.discountPercent").value(20))
                .andExpect(jsonPath("$.note").value("Sweet hour"))
                .andExpect(jsonPath("$.noteAm").value("ጣፋጭ ሰዓት"))
                .andExpect(jsonPath("$.eligibleDishes").value(2)) // available + sold out; not hidden, not free
                .andExpect(jsonPath("$.status").value("ACTIVE")); // Wednesday 12:00 within 11:00–14:00
        mockMvc.perform(get("/api/category-specials").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].categoryName").value("Desserts"));
    }

    @Test
    void invalidCategorySpecialsAreRejectedAndNothingIsSaved() throws Exception {
        fieldError(create(owner, ""), "discountPercent");
        fieldError(create(owner, "\"discountPercent\":0"), "discountPercent");
        fieldError(create(owner, "\"discountPercent\":91"), "discountPercent");
        fieldError(create(owner, "\"discountPercent\":12.5"), "discountPercent");
        fieldError(create(owner, "\"discountPercent\":20,\"startsOn\":\"2026-11-10\",\"endsOn\":\"2026-11-09\""), "endsOn");
        fieldError(create(owner, "\"discountPercent\":20,\"startTime\":\"18:00\",\"endTime\":\"16:00\""), "endTime");
        fieldError(create(owner, "\"discountPercent\":20,\"daysOfWeek\":[\"FUNDAY\"]"), "daysOfWeek");
        fieldError(create(owner, "\"discountPercent\":20,\"note\":\"" + "x".repeat(256) + "\""), "note");
        fieldError(send(post("/api/category-specials"), owner, "{\"discountPercent\":20}"), "categoryId");
        assertThat(categorySpecials.count()).isZero();
    }

    @Test
    void aCategoryHasAtMostOneCategorySpecial() throws Exception {
        createdId("\"discountPercent\":20");
        fieldError(create(owner, "\"discountPercent\":10"), "categoryId");
        assertThat(categorySpecials.count()).isEqualTo(1);
    }

    @Test
    void theOwnerEditsPausesResumesAndDeletesIt() throws Exception {
        String id = createdId("\"discountPercent\":20,\"note\":\"Sweet\"");
        send(patch("/api/category-specials/{id}", id), owner, "{\"discountPercent\":25,\"startsOn\":\"2026-11-05\"}")
                .andExpect(jsonPath("$.discountPercent").value(25)).andExpect(jsonPath("$.note").value("Sweet"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        send(patch("/api/category-specials/{id}", id), owner, "{\"clearStartsOn\":true,\"paused\":true}").andExpect(jsonPath("$.status").value("PAUSED"));
        send(patch("/api/category-specials/{id}", id), owner, "{\"paused\":false,\"note\":\"\"}").andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(categorySpecials.findById(id).orElseThrow().getNote()).isNull();
        mockMvc.perform(delete("/api/category-specials/{id}", id).header("Authorization", data.bearer(owner))).andExpect(status().isNoContent());
        assertThat(categorySpecials.count()).isZero();
    }

    @Test
    void anotherRestaurantCanNeitherUseTheCategoryNorSeeOrChangeTheSpecial() throws Exception {
        Tenant other = data.tenant("beta");
        create(other, "\"discountPercent\":20").andExpect(status().isNotFound());
        String id = createdId("\"discountPercent\":20");
        mockMvc.perform(get("/api/category-specials").header("Authorization", data.bearer(other))).andExpect(jsonPath("$", hasSize(0)));
        send(patch("/api/category-specials/{id}", id), other, "{\"paused\":true}").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/category-specials/{id}", id).header("Authorization", data.bearer(other))).andExpect(status().isNotFound());
        assertThat(categorySpecials.findById(id).orElseThrow().isPaused()).isFalse();
    }

    @Test
    void theDatabaseRefusesACategorySpecialForAnotherRestaurantsCategory() {
        Tenant other = data.tenant("beta");
        assertThatThrownBy(() -> jdbc.update("insert into category_special (id, business_id, category_id, discount_percent, days_of_week, is_paused, "
                + "\"createdAt\", \"updatedAt\") values ('cs-x', ?, ?, 10, '[]'::jsonb, false, now(), now())", other.businessId(), desserts.getId()))
                .hasMessageContaining("category_special_business_mismatch");
    }

    @Test
    void deletingTheCategoryOrItsMenuDeletesItsSpecial() throws Exception {
        // a category with dishes can't be deleted (existing rule), so an empty one shows the cascade
        Category empty = data.category(menu, "Seasonal");
        send(post("/api/category-specials"), owner, "{\"categoryId\":\"" + empty.getId() + "\",\"discountPercent\":15}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.eligibleDishes").value(0));
        mockMvc.perform(delete("/api/categories/{id}", empty.getId()).header("Authorization", data.bearer(owner))).andExpect(status().is2xxSuccessful());
        assertThat(categorySpecials.count()).isZero();

        createdId("\"discountPercent\":20");
        mockMvc.perform(delete("/api/menus/{id}", menu.getId()).header("Authorization", data.bearer(owner))).andExpect(status().is2xxSuccessful());
        assertThat(categorySpecials.count()).isZero();
    }

    @Test
    void dishSpecialsAndTheirApiAreUnchanged() throws Exception {
        createdId("\"discountPercent\":20");
        send(post("/api/specials"), owner, "{\"menuItemId\":\"" + baklava.getId() + "\",\"specialPrice\":8}").andExpect(status().isCreated());
        String body = mockMvc.perform(get("/api/specials").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$", hasSize(1))).andReturn().getResponse().getContentAsString();
        Map<String, Object> dish = JsonPath.read(body, "$[0]");
        assertThat(dish.keySet()).containsExactlyInAnyOrder("id", "menuItemId", "itemName", "menuId", "menuName", "categoryName",
                "regularPrice", "specialPrice", "currency", "note", "noteAm", "startsOn", "endsOn", "daysOfWeek", "startTime",
                "endTime", "paused", "status", "createdAt", "updatedAt");
    }

    @Test
    void guestsGetTheDiscountButNeverTheOwnerRecord() throws Exception {
        // AGF-S2-36 checkpoint B replaced checkpoint A's "nothing reaches guests": guests get the guest fields only
        String id = createdId("\"discountPercent\":20,\"note\":\"Sweet hour\"");
        for (String body : new String[]{
                mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), baklava.getId())).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner))).andReturn().getResponse().getContentAsString()}) {
            assertThat(body).contains("Baklava").contains("specialPrice").contains("Sweet hour")
                    .doesNotContain("discountPercent").doesNotContain("eligibleDishes").doesNotContain(id);
        }
    }
}
