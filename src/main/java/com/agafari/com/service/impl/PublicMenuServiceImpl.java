package com.agafari.com.service.impl;

import com.agafari.com.service.TimeZoneRules;
import com.agafari.com.dto.response.*;
import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.*;
import com.agafari.com.jpa.repository.*;
import com.agafari.com.service.PublicMenuService;
import com.agafari.com.service.CurrencyPolicy;
import com.agafari.com.service.LogoUrls;
import com.agafari.com.service.PublicVisibilityPolicy;
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
    /** AGF-S2-26 (D-S2-26-6): menu-type meal times are wall-clock times defined in Addis Ababa (D-S1-09-2). */
    private static final ZoneId MEAL_TIME_ZONE = ZoneId.of(TimeZoneRules.ADDIS_ABABA);
    private final BusinessRepository businessRepo;
    private final MenuRepository menuRepo;
    private final CategoryRepository categoryRepo;
    private final MenuItemRepository menuItemRepo;

    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

    private volatile Clock clock = Clock.systemUTC(); // AGF-S2-26: replaceable in tests

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PublicBusinessMenuResponse getBusinessMenu(String businessId) {
        log.info("getBusinessMenu", businessId);

        // 1) business
        Business business = businessRepo.findPublicById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        BusinessPublicResponse businessDto = toBusinessDto(business);

        // 2) menus (active + PUBLIC) + menuType
        List<Menu> menusRaw = menuRepo.findByBusiness_IdAndIsActiveTrueAndVisibility(
                businessId, PublicVisibilityPolicy.PUBLIC_MENU_VISIBILITY);
        if (menusRaw.isEmpty()) {
            return new PublicBusinessMenuResponse(businessDto, List.of());
        }

        // sort menus by closest menuType.updatedAt time-of-day to now
        List<Menu> menusSorted = sortMenusByClosestMenuTypeTimeOfDay(menusRaw, TimeZoneRules.zoneId(business.getTimeZone()));

        return new PublicBusinessMenuResponse(businessDto, nestGuestVisible(businessDto, menusSorted, false));
    }

    /**
     * AGF-S2-17: one of the owner's menus as guests would see it once published. The same category and item rules as
     * the guest menu apply; only the menu's own publish state and visibility are not required. The caller has
     * already resolved the owner's business; another business's menu is reported as not found.
     */
    @Override
    @Transactional(readOnly = true)
    public PublicBusinessMenuResponse getOwnerPreview(String businessId, String menuId) {
        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));
        Business business = businessRepo.findPublicById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        BusinessPublicResponse businessDto = toBusinessDto(business);
        return new PublicBusinessMenuResponse(businessDto, nestGuestVisible(businessDto, List.of(menu), true));
    }

    /**
     * Active categories and guest-visible items (AVAILABLE, OUT_OF_STOCK) of the given menus, nested in the given
     * menu order. Shared by the guest menu and the owner preview so the two can't drift apart. Menus and categories
     * without guest-visible items are dropped, except that {@code keepEmptyMenus} keeps each menu (with no categories).
     */
    private List<MenuWithCategoriesResponse> nestGuestVisible(BusinessPublicResponse businessDto, List<Menu> menus,
                                                              boolean keepEmptyMenus) {
        List<String> menuIds = menus.stream().map(Menu::getId).toList();

        // categories active
        List<Category> categories = categoryRepo.findActiveByMenuIds(menuIds);
        List<String> categoryIds = categories.stream().map(Category::getId).toList();

        // items with a guest-visible status (AVAILABLE, OUT_OF_STOCK)
        List<MenuItem> items = categoryIds.isEmpty() ? List.of()
                : menuItemRepo.findByMenu_IdInAndCategory_IdInAndStatusInOrderBySortOrderAscCreatedAtAsc(
                        menuIds, categoryIds, PublicVisibilityPolicy.PUBLIC_ITEM_STATUSES);

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

        // categoriesByMenuId
        Map<String, List<CategoryWithItemsResponse>> categoriesByMenuId =
                categoriesWithItems.stream().collect(Collectors.groupingBy(CategoryWithItemsResponse::getMenuId));

        // menus with nested categories; keep the given order; only menus with categories unless keepEmptyMenus
        return menus.stream()
                .map(menu -> {
                    List<CategoryWithItemsResponse> cats = categoriesByMenuId.getOrDefault(menu.getId(), List.of());
                    if (cats.isEmpty() && !keepEmptyMenus) return null;

                    return MenuWithCategoriesResponse.builder()
                            .id(menu.getId())
                            .name(menu.getName())
                            .description(menu.getDescription())
                            .isActive(menu.isActive())
                            .visibility(menu.getVisibility())
                            .menuType(toMenuTypeDto(menu.getMenuType()))
                            .currency(businessDto.getCurrency())
                            .categories(cats)
                            .build();
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ---------------- public item detail ----------------

    @Override
    @Transactional(readOnly = true)
    public MenuItemPublicResponse getPublicItem(String businessId, String itemId) {
        return findPublicItem(businessId, itemId);
    }

    @Override
    @Transactional(readOnly = true)
    public MenuItemPublicResponse getPublicItem(String itemId) {
        return findPublicItem(null, itemId);
    }

    // Same rule as the public menu. Hidden and non-existent items share one 404 so existence is not revealed.
    private MenuItemPublicResponse findPublicItem(String businessId, String itemId) {
        return menuItemRepo.findPubliclyVisibleItem(
                        itemId, businessId,
                        PublicVisibilityPolicy.PUBLIC_MENU_VISIBILITY,
                        PublicVisibilityPolicy.PUBLIC_ITEM_STATUSES)
                .map(this::toItemDto)
                .orElseThrow(() -> new NotFoundException("Item not found"));
    }

    // ---------------- sorting logic (same as Node) ----------------

    /** AGF-S2-26: "now" is the restaurant's local time; meal times are read in Addis Ababa, independent of the server zone. */
    private List<Menu> sortMenusByClosestMenuTypeTimeOfDay(List<Menu> menusRaw, ZoneId restaurantZone) {
        LocalTime now = LocalTime.now(clock.withZone(restaurantZone));
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
        LocalDateTime dt = LocalDateTime.ofInstant(instant, MEAL_TIME_ZONE);
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
                .currency(CurrencyPolicy.displayCurrency(b.getCurrency()))
                .logoUrl(LogoUrls.publicUrl(publicBaseUrl, b.getLogoKey()))
                .locationUrl(b.getLocationUrl())
                .country(b.getCountry())
                .hours(currentHours(b.getBusinessHours()))
                .timeZone(TimeZoneRules.zoneId(b.getTimeZone()).getId())
                .build();
    }

    /**
     * One row per day, Monday first (AGF-S2-03). Older hour edits could leave several rows for a day;
     * the most recently created one is the current one.
     */
    private static List<PublicBusinessHoursResponse> currentHours(List<BusinessHours> rows) {
        Comparator<BusinessHours> newestFirst = Comparator.comparing(BusinessHours::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        Map<DayOfWeekEnum, BusinessHours> byDay = new EnumMap<>(DayOfWeekEnum.class);
        rows.stream().sorted(newestFirst).forEach(row -> byDay.putIfAbsent(row.getDayOfWeek(), row));
        return byDay.values().stream().map(PublicBusinessHoursResponse::from).toList();
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
                .currency(CurrencyPolicy.displayCurrency(i.getMenu().getBusiness().getCurrency()))
                .build();
    }
}

