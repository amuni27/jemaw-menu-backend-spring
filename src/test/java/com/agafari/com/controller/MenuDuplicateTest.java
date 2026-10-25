package com.agafari.com.controller;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.enums.SpiceLevel;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-18: POST /api/menus/{menuId}/duplicate copies one of the owner's menus with all its categories and items into
 * a new, unpublished menu (D-S2-18-1…7). Sold-out items become available, hidden items stay hidden, images are copied
 * to the new item's own storage key (best effort), and the source menu is never changed.
 */
@ExtendWith(OutputCaptureExtension.class)
class MenuDuplicateTest extends AbstractIntegrationTest {

    private static final String SOURCE_IMAGE = "agafari/menu-items/source-item/main.jpg";

    @Autowired private MenuRepository menus;
    @Autowired private CategoryRepository categories;
    @Autowired private MenuItemRepository items;

    private Tenant owner;
    private Menu lunch;
    private Category mains;
    private Category drinks;
    private MenuItem doro;
    private MenuItem tibs;
    private MenuItem secret;
    private MenuItem tea;

    @BeforeEach
    void publishedMenuWithEverything() {
        owner = data.tenant("alpha");
        lunch = data.menu(owner, "Lunch", true, MenuVisibility.PRIVATE);
        lunch.setDescription("Weekday lunch");
        lunch = menus.save(lunch);

        mains = data.category(lunch, "Mains");
        mains.setSortOrder(0);
        mains = categories.save(mains);
        drinks = data.category(lunch, "Drinks", false); // inactive category is copied, still inactive
        drinks.setSortOrder(1);
        drinks = categories.save(drinks);

        doro = data.item(mains, "Doro Wat", MenuItemStatus.AVAILABLE);
        doro.setDescription("Chicken stew");
        doro.setPrice(new BigDecimal("12.50"));
        doro.setCalories(640);
        doro.setIngredients(List.of("chicken", "berbere"));
        doro.setAllergens(List.of("egg"));
        doro.setTags(List.of("spicy", "fasting"));
        doro.setFeatured(true);
        doro.setPrepTimeMinutes(25);
        doro.setSpiceLevel(SpiceLevel.HOT);
        doro.setImageUrl(SOURCE_IMAGE);
        doro.setSortOrder(0);
        doro = items.save(doro);

        tibs = data.item(mains, "Beef Tibs", MenuItemStatus.OUT_OF_STOCK);
        tibs.setSortOrder(1);
        tibs = items.save(tibs);
        secret = data.item(mains, "Secret Special", MenuItemStatus.UNAVAILABLE);
        secret.setSortOrder(2);
        secret = items.save(secret);
        tea = data.item(drinks, "Spiced Tea", MenuItemStatus.AVAILABLE);
    }

    private ResultActions duplicate(Menu menu, Tenant who, String body) throws Exception {
        var req = post("/api/menus/{id}/duplicate", menu.getId()).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req = req.content(body);
        return mockMvc.perform(who == null ? req : req.header("Authorization", data.bearer(who)));
    }

    private String newMenuId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.menu.id");
    }

    private List<Category> categoriesOf(String menuId) {
        return categories.findByMenu_IdAndMenu_Business_IdOrderBySortOrderAscCreatedAtAsc(menuId, owner.businessId());
    }

    private List<MenuItem> itemsOf(Category category) {
        return items.findByCategory_IdOrderBySortOrderAscCreatedAtAsc(category.getId());
    }

    private MenuItem copyOf(String menuId, String itemName) {
        return categoriesOf(menuId).stream().flatMap(c -> itemsOf(c).stream())
                .filter(i -> i.getName().equals(itemName)).findFirst().orElseThrow();
    }

    // AC1 / D-S2-18-2
    @Test
    void theCopyIsANewUnpublishedMenuWithTheSameDetails() throws Exception {
        var result = duplicate(lunch, owner, "{\"name\":\"Summer Lunch\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.menu.name").value("Summer Lunch"))
                .andExpect(jsonPath("$.categoriesCopied").value(2))
                .andExpect(jsonPath("$.itemsCopied").value(4))
                .andExpect(jsonPath("$.imagesNotCopied").value(0));

        Menu copy = menus.findById(newMenuId(result)).orElseThrow();
        assertThat(copy.getId()).isNotEqualTo(lunch.getId());
        assertThat(copy.getBusiness().getId()).isEqualTo(owner.businessId());
        assertThat(copy.getDescription()).isEqualTo("Weekday lunch");
        assertThat(copy.getMenuType().getId()).isEqualTo(lunch.getMenuType().getId());
        assertThat(copy.getVisibility()).isEqualTo(MenuVisibility.PRIVATE);
        assertThat(copy.isActive()).isFalse();
    }

    // AC2
    @Test
    void everyCategoryIsCopiedWithItsOrderAndActiveState() throws Exception {
        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));

        List<Category> copied = categoriesOf(copyId);
        assertThat(copied).extracting(Category::getName).containsExactly("Mains", "Drinks");
        assertThat(copied).extracting(Category::getSortOrder).containsExactly(0, 1);
        assertThat(copied).extracting(Category::isActive).containsExactly(true, false);
        assertThat(copied).extracting(Category::getId).doesNotContain(mains.getId(), drinks.getId());
    }

    // AC3
    @Test
    void everyItemIsCopiedWithAllItsFieldsInTheMatchingCategoryAndOrder() throws Exception {
        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));

        List<Category> copied = categoriesOf(copyId);
        assertThat(itemsOf(copied.get(0))).extracting(MenuItem::getName)
                .containsExactly("Doro Wat", "Beef Tibs", "Secret Special");
        assertThat(itemsOf(copied.get(1))).extracting(MenuItem::getName).containsExactly("Spiced Tea");

        MenuItem copy = copyOf(copyId, "Doro Wat");
        assertThat(copy.getId()).isNotEqualTo(doro.getId());
        assertThat(copy.getMenu().getId()).isEqualTo(copyId);
        assertThat(copy.getDescription()).isEqualTo("Chicken stew");
        assertThat(copy.getPrice()).isEqualByComparingTo("12.50");
        assertThat(copy.getCalories()).isEqualTo(640);
        assertThat(copy.getIngredients()).containsExactly("chicken", "berbere");
        assertThat(copy.getAllergens()).containsExactly("egg");
        assertThat(copy.getTags()).containsExactly("spicy", "fasting");
        assertThat(copy.isFeatured()).isTrue();
        assertThat(copy.getPrepTimeMinutes()).isEqualTo(25);
        assertThat(copy.getSpiceLevel()).isEqualTo(SpiceLevel.HOT);
        assertThat(copy.getSortOrder()).isEqualTo(0);
    }

    // AC4 / D-S2-18-4
    @Test
    void soldOutItemsBecomeAvailableAndHiddenItemsStayHidden() throws Exception {
        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));

        assertThat(copyOf(copyId, "Doro Wat").getStatus()).isEqualTo(MenuItemStatus.AVAILABLE);
        assertThat(copyOf(copyId, "Beef Tibs").getStatus()).isEqualTo(MenuItemStatus.AVAILABLE);
        assertThat(copyOf(copyId, "Secret Special").getStatus()).isEqualTo(MenuItemStatus.UNAVAILABLE);
    }

    // AC5 / D-S2-18-5
    @Test
    void imagesAreCopiedToTheNewItemsOwnKey() throws Exception {
        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));
        MenuItem copy = copyOf(copyId, "Doro Wat");

        var request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client, times(1)).copyObject(request.capture());
        assertThat(request.getValue().sourceKey()).isEqualTo(SOURCE_IMAGE);
        assertThat(request.getValue().destinationKey()).isEqualTo("agafari/menu-items/" + copy.getId() + "/main.jpg");
        assertThat(copy.getImageUrl()).isEqualTo("agafari/menu-items/" + copy.getId() + "/main.jpg");
        assertThat(items.findById(doro.getId()).orElseThrow().getImageUrl()).isEqualTo(SOURCE_IMAGE);
    }

    // AC5: best effort
    @Test
    void anImageThatCannotBeCopiedLeavesTheItemWithoutAnImage() throws Exception {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(SdkClientException.create("storage down"));

        String copyId = newMenuId(duplicate(lunch, owner, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemsCopied").value(4))
                .andExpect(jsonPath("$.imagesNotCopied").value(1)));

        assertThat(copyOf(copyId, "Doro Wat").getImageUrl()).isNull();
        assertThat(items.findById(doro.getId()).orElseThrow().getImageUrl()).isEqualTo(SOURCE_IMAGE);
    }

    // AC6
    @Test
    void theSourceMenuIsUnchanged() throws Exception {
        duplicate(lunch, owner, null).andExpect(status().isCreated());

        Menu source = menus.findById(lunch.getId()).orElseThrow();
        assertThat(source.getName()).isEqualTo("Lunch");
        assertThat(source.isActive()).isTrue();
        assertThat(source.getVisibility()).isEqualTo(MenuVisibility.PRIVATE);
        assertThat(categoriesOf(lunch.getId())).extracting(Category::getId).containsExactly(mains.getId(), drinks.getId());
        assertThat(itemsOf(mains)).extracting(MenuItem::getId).containsExactly(doro.getId(), tibs.getId(), secret.getId());
        assertThat(items.findById(tibs.getId()).orElseThrow().getStatus()).isEqualTo(MenuItemStatus.OUT_OF_STOCK);
        assertThat(items.findById(tea.getId()).orElseThrow().getCategory().getId()).isEqualTo(drinks.getId());
    }

    // AC7
    @Test
    void theCopyAndTheSourceAreIndependent() throws Exception {
        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));
        MenuItem copiedTibs = copyOf(copyId, "Beef Tibs");

        mockMvc.perform(delete("/api/items/{id}", copiedTibs.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        assertThat(items.findById(tibs.getId())).isPresent();

        mockMvc.perform(patch("/api/menus/{id}", lunch.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Old Lunch\"}"))
                .andExpect(status().isOk());
        assertThat(menus.findById(copyId).orElseThrow().getName()).isEqualTo("Lunch (copy)");

        mockMvc.perform(delete("/api/menus/{id}", copyId).header("Authorization", data.bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        assertThat(itemsOf(mains)).hasSize(3);
        assertThat(menus.findById(lunch.getId())).isPresent();
    }

    // AC8
    @Test
    void guestsDoNotSeeTheCopyButTheOwnerCanPreviewIt() throws Exception {
        Menu published = data.menu(owner, "Dinner");
        data.item(data.category(published, "Mains"), "Dinner Dish");
        String copyId = newMenuId(duplicate(published, owner, null).andExpect(status().isCreated()));

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(copyId))))
                .andExpect(content().string(not(containsString("Dinner (copy)"))));
        mockMvc.perform(get("/api/menus/{id}/preview", copyId).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dinner Dish")));
    }

    // AC9
    @Test
    void anotherRestaurantGetsNotFoundAndNothingIsCreated() throws Exception {
        var other = data.tenant("beta");
        long before = menus.count();

        duplicate(lunch, other, null)
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Doro Wat"))));
        duplicate(lunch, null, null).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/menus/{id}/duplicate", "no-such-menu").header("Authorization", data.bearer(owner)))
                .andExpect(status().isNotFound());

        assertThat(menus.count()).isEqualTo(before);
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    // AC10
    @Test
    void withoutANameTheCopyIsCalledNameCopy() throws Exception {
        duplicate(lunch, owner, null).andExpect(status().isCreated()).andExpect(jsonPath("$.menu.name").value("Lunch (copy)"));
        duplicate(lunch, owner, "{}").andExpect(status().isCreated()).andExpect(jsonPath("$.menu.name").value("Lunch (copy)"));
    }

    // AC10
    @Test
    void aLongDefaultNameIsShortenedToFit() throws Exception {
        lunch.setName("L".repeat(255));
        menus.save(lunch);

        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));
        String name = menus.findById(copyId).orElseThrow().getName();
        assertThat(name).hasSize(255).endsWith(" (copy)");
    }

    // AC10
    @Test
    void theNameIsTrimmedAndBlankOrTooLongNamesAreRejected() throws Exception {
        duplicate(lunch, owner, "{\"name\":\"  Fasting Menu  \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.menu.name").value("Fasting Menu"));
        long before = menus.count();

        duplicate(lunch, owner, "{\"name\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        duplicate(lunch, owner, "{\"name\":\"" + "x".repeat(256) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        assertThat(menus.count()).isEqualTo(before);
    }

    // AC11
    @Test
    void anUnexpectedFailureLeavesNoPartialMenu() throws Exception {
        long menusBefore = menus.count();
        long categoriesBefore = categories.count();
        long itemsBefore = items.count();
        // not a storage problem (those are handled per image), so the whole duplicate is rolled back
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(new IllegalStateException("unexpected"));

        duplicate(lunch, owner, null).andExpect(status().isInternalServerError());
        assertThat(menus.count()).isEqualTo(menusBefore);
        assertThat(categories.count()).isEqualTo(categoriesBefore);
        assertThat(items.count()).isEqualTo(itemsBefore);
    }

    // AC13
    @Test
    void anEmptyMenuDuplicatesIntoAnEmptyUnpublishedMenu() throws Exception {
        Menu empty = data.menu(owner, "Empty");
        String copyId = newMenuId(duplicate(empty, owner, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoriesCopied").value(0))
                .andExpect(jsonPath("$.itemsCopied").value(0)));

        assertThat(categoriesOf(copyId)).isEmpty();
        assertThat(menus.findById(copyId).orElseThrow().isActive()).isFalse();
    }

    // AC14
    @Test
    void logsDoNotContainImageKeys(CapturedOutput output) throws Exception {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(SdkClientException.create("storage down " + SOURCE_IMAGE));
        duplicate(lunch, owner, null).andExpect(status().isCreated());

        assertThat(output.getAll()).contains("event=menu.duplicate");
        assertThat(output.getAll()).doesNotContain(SOURCE_IMAGE).doesNotContain("source-item");
    }

    @Test
    void menusAreListedNewestFirstWithTheCopyIncluded() throws Exception {
        String copyId = newMenuId(duplicate(lunch, owner, null).andExpect(status().isCreated()));
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(copyId));
        assertThat(menus.findAll()).extracting(Menu::getName).containsExactlyInAnyOrder("Lunch", "Lunch (copy)");
    }
}
