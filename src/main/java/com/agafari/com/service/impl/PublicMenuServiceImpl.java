package com.agafari.com.service.impl;

import com.agafari.com.dto.response.*;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.*;
import com.agafari.com.jpa.repository.*;
import com.agafari.com.service.PublicMenuService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PublicMenuServiceImpl implements PublicMenuService {

    private static final Logger log = LoggerFactory.getLogger(PublicMenuServiceImpl.class);
    private final BusinessRepository businessRepo;
    private final MenuRepository menuRepo;
    private final CategoryRepository categoryRepo;
    private final MenuItemRepository menuItemRepo;

    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

    @Override
    @Transactional(readOnly = true)
    public PublicBusinessMenuResponse getBusinessMenu(String businessId) {
        log.info("getBusinessMenu", businessId);

        // 1) business
        Business business = businessRepo.findPublicById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        BusinessPublicResponse businessDto = toBusinessDto(business);

        // 2) menus (active) + menuType
        List<Menu> menusRaw = menuRepo.findActiveMenusWithMenuType(businessId);
        if (menusRaw.isEmpty()) {
            return new PublicBusinessMenuResponse(businessDto, List.of());
        }

        // sort menus by closest menuType.updatedAt time-of-day to now
        List<Menu> menusSorted = sortMenusByClosestMenuTypeTimeOfDay(menusRaw);

        List<String> menuIds = menusSorted.stream().map(Menu::getId).toList();

        // 3) categories active
        List<Category> categories = categoryRepo.findActiveByMenuIds(menuIds);
        if (categories.isEmpty()) {
            return new PublicBusinessMenuResponse(businessDto, List.of());
        }

        List<String> categoryIds = categories.stream().map(Category::getId).toList();

        // 4) items AVAILABLE only
        List<MenuItem> items = menuItemRepo.findAvailableByMenuIdsAndCategoryIds(menuIds, categoryIds);

        // itemsByCategoryId
        Map<String, List<MenuItem>> itemsByCategoryId =
                items.stream().collect(Collectors.groupingBy(i -> i.getCategory().getId()));

        // categories with items
        List<CategoryWithItemsResponse> categoriesWithItems = categories.stream()
                .map(cat -> {
                    List<MenuItem> catItems = itemsByCategoryId.getOrDefault(cat.getId(), List.of());
                    if (catItems.isEmpty()) return null;
                    return CategoryWithItemsResponse.builder()
                            .id(cat.getId())
                            .menuId(cat.getMenu().getId())
                            .name(cat.getName())
                            .sortOrder(cat.getSortOrder())
                            .isActive(cat.isActive())
                            .items(catItems.stream().map(this::toItemDto).toList())
                            .build();
                })
                .filter(Objects::nonNull)
                .toList();

        if (categoriesWithItems.isEmpty()) {
            return new PublicBusinessMenuResponse(businessDto, List.of());
        }

        // categoriesByMenuId
        Map<String, List<CategoryWithItemsResponse>> categoriesByMenuId =
                categoriesWithItems.stream().collect(Collectors.groupingBy(CategoryWithItemsResponse::getMenuId));

        // 5) menus with nested categories; keep sorted order; only menus with categories
        List<MenuWithCategoriesResponse> menusNested = menusSorted.stream()
                .map(menu -> {
                    List<CategoryWithItemsResponse> cats = categoriesByMenuId.getOrDefault(menu.getId(), List.of());
                    if (cats.isEmpty()) return null;

                    return MenuWithCategoriesResponse.builder()
                            .id(menu.getId())
                            .name(menu.getName())
                            .description(menu.getDescription())
                            .isActive(menu.isActive())
                            .visibility(menu.getVisibility())
                            .menuType(toMenuTypeDto(menu.getMenuType()))
                            .categories(cats)
                            .build();
                })
                .filter(Objects::nonNull)
                .toList();

        return new PublicBusinessMenuResponse(businessDto, menusNested);
    }

    // ---------------- sorting logic (same as Node) ----------------

    private List<Menu> sortMenusByClosestMenuTypeTimeOfDay(List<Menu> menusRaw) {
        LocalTime now = LocalTime.now();
        int nowMin = now.getHour() * 60 + now.getMinute();

        return menusRaw.stream()
                .sorted((a, b) -> {
                    MenuType at = a.getMenuType();
                    MenuType bt = b.getMenuType();

                    if (at == null && bt == null) return 0;
                    if (at == null) return 1;
                    if (bt == null) return -1;

                    int aMin = minutesSinceMidnight(at.getUpdatedAt());
                    int bMin = minutesSinceMidnight(bt.getUpdatedAt());

                    int da = circularDiffMinutes(nowMin, aMin);
                    int db = circularDiffMinutes(nowMin, bMin);

                    if (da != db) return Integer.compare(da, db);

                    // tie breaker: later updatedAt first
                    Instant ai = at.getUpdatedAt();
                    Instant bi = bt.getUpdatedAt();
                    return bi.compareTo(ai);
                })
                .toList();
    }

    private int minutesSinceMidnight(Instant instant) {
        // if updatedAt is Instant in UTC. Convert to system default like Node's Date.
        LocalDateTime dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return dt.getHour() * 60 + dt.getMinute();
    }

    private int circularDiffMinutes(int a, int b) {
        int diff = Math.abs(a - b);
        return Math.min(diff, 1440 - diff);
    }

    // ---------------- mapping ----------------

    private BusinessPublicResponse toBusinessDto(Business b) {
        return BusinessPublicResponse.builder()
                .id(b.getId())
                .name(b.getName())
                .businessPhone(b.getBusinessPhone())
                .streetAddress(b.getStreetAddress())
                .city(b.getCity())
                .state(b.getState())
                .zipcode(b.getZipcode())
                .customSubdomain(b.getCustomSubdomain())
                .open24_7(b.isOpen24_7())
                .build();
    }

    private MenuTypeResponse toMenuTypeDto(MenuType t) {
        if (t == null) return null;
        return MenuTypeResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    private MenuItemPublicResponse toItemDto(MenuItem i) {
        String imageUrl = i.getImageUrl();

        if (imageUrl != null && !imageUrl.startsWith("http")) {
            imageUrl = publicBaseUrl.replaceAll("/$", "") + "/" +
                    imageUrl.replaceAll("^/", "");
        }
        return MenuItemPublicResponse.builder()
                .id(i.getId())
                .name(i.getName())
                .description(i.getDescription())
                .price(i.getPrice())
                .imageUrl(imageUrl)
                .calories(i.getCalories())
                .isFeatured(i.isFeatured())
                .prepTimeMinutes(i.getPrepTimeMinutes())
                .spiceLevel(i.getSpiceLevel())
                .status(i.getStatus())
                .sortOrder(i.getSortOrder())
                .ingredients(i.getIngredients())
                .allergens(i.getAllergens())
                .tags(i.getTags())
                .build();
    }
}

