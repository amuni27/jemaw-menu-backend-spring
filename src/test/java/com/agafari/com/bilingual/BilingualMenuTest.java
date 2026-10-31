package com.agafari.com.bilingual;

import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-23 (checkpoint A): optional Amharic names (menu, category, item) and item descriptions. Owners create,
 * change and clear them (blank clears, omitted keeps); 255-character limit with S1-10 field errors; the guest menu,
 * owner preview and owner reads carry them; duplicate copies them; other restaurants can't change them.
 */
class BilingualMenuTest extends AbstractIntegrationTest {

    private static final String DORO = "ዶሮ ወጥ";
    private static final String DORO_DESC = "በበርበሬ የተሰራ የዶሮ ወጥ ከእንቁላል ጋር";
    private static final String MAINS = "ዋና ምግቦች";
    private static final String LUNCH = "ምሳ";

    @Autowired private MenuRepository menus;
    @Autowired private CategoryRepository categories;
    @Autowired private MenuItemRepository items;

    private Tenant owner;
    private Menu menu;
    private Category mains;
    private MenuItem doro;

    @BeforeEach
    void publishedMenu() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        mains = data.category(menu, "Mains");
        doro = data.item(mains, "Doro Wat");
    }

    private ResultActions send(MockHttpServletRequestBuilder req, Tenant who, String json) throws Exception {
        return mockMvc.perform(req.header("Authorization", data.bearer(who)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ---- owner editing ---------------------------------------------------------------------------------------------

    @Test
    void anItemGetsChangesKeepsAndClearsItsAmharicText() throws Exception {
        send(patch("/api/items/{id}", doro.getId()), owner, "{\"nameAm\":\" " + DORO + " \",\"descriptionAm\":\"" + DORO_DESC + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameAm").value(DORO))
                .andExpect(jsonPath("$.descriptionAm").value(DORO_DESC));
        mockMvc.perform(get("/api/items/{id}", doro.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.nameAm").value(DORO)); // reopening the form

        send(patch("/api/items/{id}", doro.getId()), owner, "{\"price\":12}").andExpect(status().isOk());
        assertThat(items.findById(doro.getId()).orElseThrow().getNameAm()).isEqualTo(DORO); // omitted = unchanged

        send(patch("/api/items/{id}", doro.getId()), owner, "{\"nameAm\":\"\",\"descriptionAm\":\"  \"}").andExpect(status().isOk());
        MenuItem cleared = items.findById(doro.getId()).orElseThrow();
        assertThat(cleared.getNameAm()).isNull(); // blank = clear
        assertThat(cleared.getDescriptionAm()).isNull();
        assertThat(cleared.getName()).isEqualTo("Doro Wat");
    }

    @Test
    void newItemsCategoriesAndMenusCanStartWithAmharic() throws Exception {
        String item = send(post("/api/menus/{id}/items", menu.getId()), owner, "{\"categoryId\":\"" + mains.getId()
                + "\",\"name\":\"Shiro\",\"nameAm\":\"ሽሮ\",\"descriptionAm\":\"የሽምብራ ወጥ\",\"price\":8,\"ingredients\":[\"chickpea\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String itemId = JsonPath.read(item, "$.item.id");
        assertThat(items.findById(itemId).orElseThrow().getNameAm()).isEqualTo("ሽሮ");
        assertThat(items.findById(itemId).orElseThrow().getDescriptionAm()).isEqualTo("የሽምብራ ወጥ");

        send(post("/api/menus/{id}/categories", menu.getId()), owner, "{\"name\":\"Drinks\",\"nameAm\":\"መጠጦች\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nameAm").value("መጠጦች"));

        send(post("/api/menus"), owner, "{\"name\":\"Dinner\",\"nameAm\":\"እራት\",\"menuTypeId\":\"mt-dinner\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nameAm").value("እራት"));
    }

    @Test
    void categoriesAndMenusGetAndClearTheirAmharicName() throws Exception {
        send(patch("/api/categories/{id}", mains.getId()), owner, "{\"nameAm\":\"" + MAINS + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameAm").value(MAINS));
        send(patch("/api/menus/{id}", menu.getId()), owner, "{\"nameAm\":\"" + LUNCH + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameAm").value(LUNCH));
        assertThat(categories.findById(mains.getId()).orElseThrow().getNameAm()).isEqualTo(MAINS);
        assertThat(menus.findById(menu.getId()).orElseThrow().getNameAm()).isEqualTo(LUNCH);

        send(patch("/api/categories/{id}", mains.getId()), owner, "{\"nameAm\":\"\"}").andExpect(status().isOk());
        send(patch("/api/menus/{id}", menu.getId()), owner, "{\"nameAm\":\"\"}").andExpect(status().isOk());
        assertThat(categories.findById(mains.getId()).orElseThrow().getNameAm()).isNull();
        assertThat(menus.findById(menu.getId()).orElseThrow().getNameAm()).isNull();
    }

    @Test
    void amharicTextLongerThan255CharactersIsRejectedWithAFieldError() throws Exception {
        String tooLong = "ሀ".repeat(256);
        String ok = "ሀ".repeat(255);
        send(patch("/api/items/{id}", doro.getId()), owner, "{\"descriptionAm\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("descriptionAm"));
        send(patch("/api/items/{id}", doro.getId()), owner, "{\"nameAm\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("nameAm"));
        send(patch("/api/categories/{id}", mains.getId()), owner, "{\"nameAm\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("nameAm"));
        send(patch("/api/menus/{id}", menu.getId()), owner, "{\"nameAm\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("nameAm"));
        assertThat(items.findById(doro.getId()).orElseThrow().getNameAm()).isNull();

        send(patch("/api/items/{id}", doro.getId()), owner, "{\"nameAm\":\"" + ok + "\"}").andExpect(status().isOk());
        assertThat(items.findById(doro.getId()).orElseThrow().getNameAm()).hasSize(255);
    }

    @Test
    void anotherRestaurantCannotChangeTheAmharicText() throws Exception {
        Tenant other = data.tenant("beta");
        // another owner's menu, category or item is reported as not found (S1-06 tenant rule)
        send(patch("/api/items/{id}", doro.getId()), other, "{\"nameAm\":\"ተሰርቋል\"}").andExpect(status().isNotFound());
        send(patch("/api/categories/{id}", mains.getId()), other, "{\"nameAm\":\"ተሰርቋል\"}").andExpect(status().isNotFound());
        send(patch("/api/menus/{id}", menu.getId()), other, "{\"nameAm\":\"ተሰርቋል\"}").andExpect(status().isNotFound());
        assertThat(items.findById(doro.getId()).orElseThrow().getNameAm()).isNull();
        assertThat(categories.findById(mains.getId()).orElseThrow().getNameAm()).isNull();
        assertThat(menus.findById(menu.getId()).orElseThrow().getNameAm()).isNull();
    }

    // ---- guest data, preview, duplicate ----------------------------------------------------------------------------

    private void translateAll() {
        menu.setNameAm(LUNCH);
        menus.save(menu);
        mains.setNameAm(MAINS);
        categories.save(mains);
        doro.setNameAm(DORO);
        doro.setDescriptionAm(DORO_DESC);
        items.save(doro);
    }

    @Test
    void theGuestMenuAndThePreviewCarryTheAmharicText() throws Exception {
        translateAll();
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].nameAm").value(LUNCH))
                .andExpect(jsonPath("$.menus[0].categories[0].nameAm").value(MAINS))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].nameAm").value(DORO))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].descriptionAm").value(DORO_DESC))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].name").value("Doro Wat")); // main text unchanged
        mockMvc.perform(get("/venue/{id}/items/{itemId}", owner.businessId(), doro.getId()))
                .andExpect(jsonPath("$.nameAm").value(DORO));
        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.menus[0].categories[0].items[0].nameAm").value(DORO));
    }

    @Test
    void hiddenItemsDontLeakTheirTranslationToGuests() throws Exception {
        translateAll();
        MenuItem hidden = data.item(mains, "Secret Special", com.agafari.com.enums.MenuItemStatus.UNAVAILABLE);
        hidden.setNameAm("ሚስጥር");
        items.save(hidden);
        String body = mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("ሚስጥር").contains(DORO);
    }

    @Test
    void duplicatingAMenuCopiesItsAmharicText() throws Exception {
        translateAll();
        String body = send(post("/api/menus/{id}/duplicate", menu.getId()), owner, "{\"name\":\"Summer Lunch\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String copyId = JsonPath.read(body, "$.menu.id");
        assertThat(menus.findById(copyId).orElseThrow().getNameAm()).isEqualTo(LUNCH);
        Category copiedMains = categories.findAll().stream().filter(c -> c.getMenu().getId().equals(copyId)).findFirst().orElseThrow();
        assertThat(copiedMains.getNameAm()).isEqualTo(MAINS);
        MenuItem copiedDoro = items.findAll().stream().filter(i -> i.getMenu().getId().equals(copyId)).findFirst().orElseThrow();
        assertThat(copiedDoro.getNameAm()).isEqualTo(DORO);
        assertThat(copiedDoro.getDescriptionAm()).isEqualTo(DORO_DESC);
    }

    @Test
    void ownerListsCarryTheAmharicText() throws Exception {
        translateAll();
        mockMvc.perform(get("/api/menus/{id}/categories", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$[*].nameAm", hasItem(MAINS)));
    }
}
