package com.agafari.com.security;

import com.agafari.com.enums.UserRole;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.BusinessHoursRepository;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-06: two restaurant owners, A and B. For every protected endpoint (handover §8.3,
 * "Profile and business" and "Menus, categories and items"), owner A tries to read or change
 * B's resources and is refused, and B's data is unchanged afterwards.
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessHoursRepository businessHours;
    @Autowired private MenuRepository menus;
    @Autowired private CategoryRepository categories;
    @Autowired private MenuItemRepository items;
    @Autowired private UserRepository users;

    /** Owner A (the caller) and owner B (the target), each with a menu, a category and an item. */
    record Fixture(Tenant a, Menu aMenu, Category aCat, MenuItem aItem,
                   Tenant b, Menu bMenu, Category bCat, MenuItem bItem) {
    }

    private Fixture f;

    @BeforeEach
    void twoTenants() {
        Tenant a = data.tenant("alpha");
        Menu aMenu = data.menu(a, "Alpha Lunch");
        Category aCat = data.category(aMenu, "Alpha Mains");
        MenuItem aItem = data.item(aCat, "Alpha Burger");
        data.hours(a);

        Tenant b = data.tenant("bravo");
        Menu bMenu = data.menu(b, "Bravo Lunch");
        Category bCat = data.category(bMenu, "Bravo Mains");
        MenuItem bItem = data.item(bCat, "Bravo Pasta");
        data.hours(b);

        f = new Fixture(a, aMenu, aCat, aItem, b, bMenu, bCat, bItem);
    }

    // ---------------------------------------------------------------------------------------
    // Business-ID path endpoints: authenticated but not yours -> 403 (AC1, AC2)
    // ---------------------------------------------------------------------------------------

    private static final Map<String, Function<Fixture, MockHttpServletRequestBuilder>> BUSINESS_ENDPOINTS = new LinkedHashMap<>();

    static {
        BUSINESS_ENDPOINTS.put("GET /api/business/{id}/hours",
                x -> get("/api/business/{id}/hours", x.b().businessId()));
        BUSINESS_ENDPOINTS.put("PATCH /api/business/{id}/hours",
                x -> json(patch("/api/business/{id}/hours", x.b().businessId()), "{\"open24_7\":true,\"businessHours\":[]}"));
        BUSINESS_ENDPOINTS.put("PATCH /api/business/{id}",
                x -> json(patch("/api/business/{id}", x.b().businessId()), "{\"name\":\"Hijacked\",\"customSubdomain\":\"hijacked\"}"));
        BUSINESS_ENDPOINTS.put("POST /api/business/{id}/logo/presign",
                x -> json(post("/api/business/{id}/logo/presign", x.b().businessId()), "{\"fileName\":\"logo.png\",\"contentType\":\"image/png\"}"));
        BUSINESS_ENDPOINTS.put("POST /api/business/{id}/logo/confirm",
                x -> json(post("/api/business/{id}/logo/confirm", x.b().businessId()), "{\"objectKey\":\"business-logo-asset/x/main.png\"}"));
        BUSINESS_ENDPOINTS.put("DELETE /api/business/{id}/logo", // AGF-S2-01
                x -> delete("/api/business/{id}/logo", x.b().businessId()));
    }

    static Stream<String> businessEndpoints() {
        return BUSINESS_ENDPOINTS.keySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("businessEndpoints")
    void ownerCannotReadOrChangeAnotherOwnersBusiness(String endpoint) throws Exception {
        String before = snapshot(f.b());

        mockMvc.perform(BUSINESS_ENDPOINTS.get(endpoint).apply(f).header("Authorization", data.bearer(f.a())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(content().string(not(containsString("dayOfWeek"))))
                .andExpect(content().string(not(containsString("09:00"))));

        assertThat(snapshot(f.b())).as("B's data after A called %s", endpoint).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------
    // Menu, category and item IDs: not found within the caller's tenant -> 404 (AC3)
    // ---------------------------------------------------------------------------------------

    private static final Map<String, Function<Fixture, MockHttpServletRequestBuilder>> TENANT_RESOURCE_ENDPOINTS = new LinkedHashMap<>();

    static {
        TENANT_RESOURCE_ENDPOINTS.put("GET /api/menus/{menuId}",
                x -> get("/api/menus/{id}", x.bMenu().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("PATCH /api/menus/{menuId}",
                x -> json(patch("/api/menus/{id}", x.bMenu().getId()), "{\"name\":\"Hijacked\",\"isActive\":false}"));
        TENANT_RESOURCE_ENDPOINTS.put("DELETE /api/menus/{menuId}",
                x -> delete("/api/menus/{id}", x.bMenu().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("GET /api/menus/{menuId}/categories",
                x -> get("/api/menus/{id}/categories", x.bMenu().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("POST /api/menus/{menuId}/categories",
                x -> json(post("/api/menus/{id}/categories", x.bMenu().getId()), "{\"name\":\"Hijacked\"}"));
        TENANT_RESOURCE_ENDPOINTS.put("POST /api/menus/{menuId}/categories/reorder",
                x -> json(post("/api/menus/{id}/categories/reorder", x.bMenu().getId()), "{\"orderedIds\":[\"" + x.bCat().getId() + "\"]}"));
        TENANT_RESOURCE_ENDPOINTS.put("PATCH /api/categories/{categoryId}",
                x -> json(patch("/api/categories/{id}", x.bCat().getId()), "{\"name\":\"Hijacked\",\"isActive\":false}"));
        TENANT_RESOURCE_ENDPOINTS.put("DELETE /api/categories/{categoryId}",
                x -> delete("/api/categories/{id}", x.bCat().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("GET /api/menus/{menuId}/items",
                x -> get("/api/menus/{id}/items", x.bMenu().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("POST /api/menus/{menuId}/items",
                x -> json(post("/api/menus/{id}/items", x.bMenu().getId()),
                        "{\"categoryId\":\"" + x.bCat().getId() + "\",\"name\":\"Hijacked\",\"price\":1,\"ingredients\":[\"x\"]}"));
        TENANT_RESOURCE_ENDPOINTS.put("GET /api/items/{itemId}",
                x -> get("/api/items/{id}", x.bItem().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("PATCH /api/items/{itemId}",
                x -> json(patch("/api/items/{id}", x.bItem().getId()), "{\"name\":\"Hijacked\",\"price\":0.5}"));
        TENANT_RESOURCE_ENDPOINTS.put("DELETE /api/items/{itemId}",
                x -> delete("/api/items/{id}", x.bItem().getId()));
        TENANT_RESOURCE_ENDPOINTS.put("PUT /api/items/{itemId}/status",
                x -> json(put("/api/items/{id}/status", x.bItem().getId()), "{\"status\":\"UNAVAILABLE\"}"));
        TENANT_RESOURCE_ENDPOINTS.put("GET /api/items/{itemId}/image/presign",
                x -> get("/api/items/{id}/image/presign", x.bItem().getId()).param("contentType", "image/png"));
        TENANT_RESOURCE_ENDPOINTS.put("POST /api/items/{itemId}/image/confirm",
                x -> json(post("/api/items/{id}/image/confirm", x.bItem().getId()), "{\"objectKey\":\"menu-items/x.jpg\"}"));
    }

    static Stream<String> tenantResourceEndpoints() {
        return TENANT_RESOURCE_ENDPOINTS.keySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantResourceEndpoints")
    void ownerGetsNotFoundForAnotherOwnersMenusCategoriesAndItems(String endpoint) throws Exception {
        String before = snapshot(f.b());

        mockMvc.perform(TENANT_RESOURCE_ENDPOINTS.get(endpoint).apply(f).header("Authorization", data.bearer(f.a())))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Bravo"))));

        assertThat(snapshot(f.b())).as("B's data after A called %s", endpoint).isEqualTo(before);
    }

    @Test
    void menuListShowsOnlyTheCallersMenus() throws Exception {
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(f.a())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(f.aMenu().getId()))
                .andExpect(content().string(not(containsString(f.bMenu().getId()))));
    }

    // ---------------------------------------------------------------------------------------
    // Foreign IDs smuggled into the caller's own requests (AC4)
    // ---------------------------------------------------------------------------------------

    @Test
    void creatingAnItemWithAnotherOwnersCategoryIsRejected() throws Exception {
        String before = snapshot(f.b());
        long aItemsBefore = items.countByCategory_Id(f.aCat().getId());

        mockMvc.perform(json(post("/api/menus/{id}/items", f.aMenu().getId()),
                                "{\"categoryId\":\"" + f.bCat().getId() + "\",\"name\":\"Smuggled\",\"price\":1,\"ingredients\":[\"x\"]}")
                        .header("Authorization", data.bearer(f.a())))
                .andExpect(status().is(anyOf(is(400), is(404))));

        assertThat(items.findAll()).noneMatch(i -> "Smuggled".equals(i.getName()));
        assertThat(items.countByCategory_Id(f.aCat().getId())).isEqualTo(aItemsBefore);
        assertThat(snapshot(f.b())).isEqualTo(before);
    }

    @Test
    void movingAnOwnItemIntoAnotherOwnersCategoryIsRejected() throws Exception {
        String before = snapshot(f.b());

        mockMvc.perform(json(patch("/api/items/{id}", f.aItem().getId()), "{\"categoryId\":\"" + f.bCat().getId() + "\"}")
                        .header("Authorization", data.bearer(f.a())))
                .andExpect(status().is(anyOf(is(400), is(404))));

        assertThat(items.findByIdAndMenu_Business_Id(f.aItem().getId(), f.a().businessId()).orElseThrow()
                .getCategory().getId()).isEqualTo(f.aCat().getId());
        assertThat(snapshot(f.b())).isEqualTo(before);
    }

    @Test
    void reorderingWithAnotherOwnersCategoryIsRejected() throws Exception {
        String before = snapshot(f.b());

        mockMvc.perform(json(post("/api/menus/{id}/categories/reorder", f.aMenu().getId()),
                                "{\"orderedIds\":[\"" + f.bCat().getId() + "\",\"" + f.aCat().getId() + "\"]}")
                        .header("Authorization", data.bearer(f.a())))
                .andExpect(status().is(anyOf(is(400), is(404))));

        assertThat(snapshot(f.b())).isEqualTo(before);
    }

    @Test
    void filteringOwnItemsByAnotherOwnersCategoryReturnsNothing() throws Exception {
        mockMvc.perform(get("/api/menus/{id}/items", f.aMenu().getId()).param("categoryId", f.bCat().getId())
                        .header("Authorization", data.bearer(f.a())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------------------------------------------------------------------------------------
    // Profile: /api/user acts only on the caller
    // ---------------------------------------------------------------------------------------

    @Test
    void profileUpdatesNeverTouchAnotherOwner() throws Exception {
        String bEmail = f.b().owner().getEmail();

        mockMvc.perform(json(patch("/api/user"), "{\"email\":\"" + bEmail + "\",\"currentPassword\":\"" + TestDataFactory.PASSWORD + "\"}").header("Authorization", data.bearer(f.a())))
                .andExpect(status().isConflict());
        mockMvc.perform(json(patch("/api/user"), "{\"fullName\":\"Alpha Renamed\"}").header("Authorization", data.bearer(f.a())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alpha Renamed"));

        var b = users.findById(f.b().owner().getId()).orElseThrow();
        assertThat(b.getEmail()).isEqualTo(bEmail);
        assertThat(b.getFullName()).isEqualTo("bravo Owner");
    }

    // ---------------------------------------------------------------------------------------
    // Owners can still use their own resources (no over-blocking)
    // ---------------------------------------------------------------------------------------

    @Test
    void ownerCanStillUseOwnBusinessEndpoints() throws Exception {
        String bearer = data.bearer(f.a());
        String id = f.a().businessId();

        mockMvc.perform(get("/api/business/{id}/hours", id).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(json(patch("/api/business/{id}/hours", id), "{\"open24_7\":true,\"businessHours\":[]}").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(json(patch("/api/business/{id}", id), "{\"name\":\"Alpha Bistro\"}").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alpha Bistro"));

        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://r2.test.invalid/upload").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
        mockMvc.perform(json(post("/api/business/{id}/logo/presign", id), "{\"fileName\":\"logo.png\",\"contentType\":\"image/png\"}")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("business-logo-asset/" + id + "/main.png"));
    }

    @Test
    void ownerCanStillUseOwnMenusCategoriesAndItems() throws Exception {
        String bearer = data.bearer(f.a());

        mockMvc.perform(get("/api/menus/{id}", f.aMenu().getId()).header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/menus/{id}/categories", f.aMenu().getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/menus/{id}/items", f.aMenu().getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/items/{id}", f.aItem().getId()).header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/items/{id}/status", f.aItem().getId()), "{\"status\":\"OUT_OF_STOCK\"}").header("Authorization", bearer))
                .andExpect(status().isOk());
        mockMvc.perform(json(patch("/api/categories/{id}", f.aCat().getId()), "{\"name\":\"Alpha Grill\"}").header("Authorization", bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/menus/type/all").header("Authorization", bearer)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------
    // No token (AC5) and filter-level authentication for /api/business/**
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("businessEndpoints")
    void businessEndpointsRejectRequestsWithoutToken(String endpoint) throws Exception {
        String before = snapshot(f.b());

        // S1-06 AC5, finalized by AGF-S1-07's JSON 401 entry point
        mockMvc.perform(BUSINESS_ENDPOINTS.get(endpoint).apply(f))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(content().string(not(containsString("dayOfWeek"))));

        assertThat(snapshot(f.b())).isEqualTo(before);
    }

    @Test
    void businessPathsRequireAuthenticationAtTheFilterLevel() throws Exception {
        // An unmapped path under /api/business is refused before routing (it was a 404 while the path was permitAll)
        mockMvc.perform(get("/api/business/{id}/no-such-endpoint", f.b().businessId()))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------
    // Roles: owner APIs require VENUE (AC6)
    // ---------------------------------------------------------------------------------------

    static Stream<String> ownerApiSamples() {
        return Stream.of("GET /api/menus", "GET /api/menus/{own}", "GET /api/menus/{own}/categories",
                "GET /api/menus/{own}/items", "GET /api/business/{own}/hours", "PATCH /api/user", "GET /api/menus/type/all");
    }

    @ParameterizedTest(name = "ADMIN {0}")
    @MethodSource("ownerApiSamples")
    void adminRoleIsDeniedOnOwnerApis(String endpoint) throws Exception {
        // An ADMIN that even owns a business with a menu: only the role check can deny it
        Tenant admin = data.tenant("admin", UserRole.ADMIN);
        Menu adminMenu = data.menu(admin, "Admin Menu");
        data.hours(admin);

        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "GET /api/menus" -> get("/api/menus");
            case "GET /api/menus/{own}" -> get("/api/menus/{id}", adminMenu.getId());
            case "GET /api/menus/{own}/categories" -> get("/api/menus/{id}/categories", adminMenu.getId());
            case "GET /api/menus/{own}/items" -> get("/api/menus/{id}/items", adminMenu.getId());
            case "GET /api/business/{own}/hours" -> get("/api/business/{id}/hours", admin.businessId());
            case "PATCH /api/user" -> json(patch("/api/user"), "{\"fullName\":\"Admin Renamed\"}");
            case "GET /api/menus/type/all" -> get("/api/menus/type/all");
            default -> throw new IllegalArgumentException(endpoint);
        };

        mockMvc.perform(request.header("Authorization", data.bearer(admin)))
                .andExpect(status().isForbidden());
        assertThat(users.findById(admin.owner().getId()).orElseThrow().getFullName()).isEqualTo("admin Owner");
    }

    // ---------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Everything B owns that an endpoint could change, as one comparable string. */
    private String snapshot(Tenant t) {
        var business = businesses.findById(t.businessId()).orElseThrow();
        Set<String> menuIds = menus.findByBusiness_IdOrderByCreatedAtDesc(t.businessId()).stream()
                .map(Menu::getId).collect(Collectors.toSet());

        List<String> parts = new java.util.ArrayList<>();
        parts.add("business=" + business.getName() + "|" + business.getCustomSubdomain() + "|" + business.getLogoKey()
                + "|" + business.isOpen24_7() + "|" + business.getCurrency());
        businessHours.findAll().stream()
                .filter(h -> h.getBusiness().getId().equals(t.businessId()))
                .sorted(Comparator.comparing(h -> h.getDayOfWeek().ordinal()))
                .forEach(h -> parts.add("hours=" + h.getDayOfWeek() + "|" + h.isOpen() + "|" + h.getStartTime() + "|" + h.getEndTime()));
        menus.findByBusiness_IdOrderByCreatedAtDesc(t.businessId())
                .forEach(m -> parts.add("menu=" + m.getId() + "|" + m.getName() + "|" + m.getDescription() + "|" + m.isActive() + "|" + m.getVisibility()));
        categories.findAll().stream()
                .filter(c -> menuIds.contains(c.getMenu().getId()))
                .sorted(Comparator.comparing(Category::getId))
                .forEach(c -> parts.add("category=" + c.getId() + "|" + c.getName() + "|" + c.getSortOrder() + "|" + c.isActive()));
        items.findAll().stream()
                .filter(i -> menuIds.contains(i.getMenu().getId()))
                .sorted(Comparator.comparing(MenuItem::getId))
                .forEach(i -> parts.add("item=" + i.getId() + "|" + i.getName() + "|" + i.getPrice() + "|" + i.getStatus()
                        + "|" + i.getCategory().getId() + "|" + i.getImageUrl() + "|" + i.getSortOrder()));
        return String.join("\n", parts);
    }
}
