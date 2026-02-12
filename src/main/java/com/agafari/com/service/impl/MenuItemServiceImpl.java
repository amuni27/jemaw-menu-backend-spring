package com.agafari.com.service.impl;

import com.agafari.com.dto.request.MenuItemCreateRequest;
import com.agafari.com.dto.request.MenuItemStatusUpdateRequest;
import com.agafari.com.dto.request.MenuItemUpdateRequest;
import com.agafari.com.dto.response.MenuItemResponse;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.MenuItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuItemServiceImpl implements MenuItemService {

    private final CurrentUser currentUser;
    private final MenuRepository menuRepo;
    private final CategoryRepository categoryRepo;
    private final MenuItemRepository itemRepo;

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(String menuId, String categoryId, MenuItemStatus status, String search) {
        String businessId = currentUser.businessId();

        if (!menuRepo.existsByIdAndBusiness_Id(menuId, businessId)) {
            throw new NotFoundException("Menu not found");
        }

        return itemRepo.searchMenuItems(menuId, businessId, categoryId, status, normalizeSearch(search))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public MenuItemResponse create(String menuId, MenuItemCreateRequest req) {
        String businessId = currentUser.businessId();

        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        Category category = categoryRepo
                .findByIdAndMenu_IdAndMenu_Business_Id(req.getCategoryId(), menuId, businessId)
                .orElseThrow(() -> new BadRequestException("categoryId must belong to the menu"));

        List<String> ingredients = normalizeIngredients(req.getIngredients());

        MenuItem item = new MenuItem();
        item.setMenu(menu);
        item.setCategory(category);
        item.setName(req.getName());
        item.setDescription(nullIfBlank(req.getDescription()));
        item.setPrice(money(req.getPrice()));
        item.setImageUrl(normalizeUrl(req.getImageUrl()));
        item.setIngredients(ingredients);
        item.setAllergens(normalizeNullableList(req.getAllergens()) == null ? List.of() : normalizeNullableList(req.getAllergens()));
        item.setTags(normalizeNullableList(req.getTags()) == null ? List.of() : normalizeNullableList(req.getTags()));
        item.setCalories(req.getCalories());
        item.setFeatured(Boolean.TRUE.equals(req.getIsFeatured()));
        item.setPrepTimeMinutes(req.getPrepTimeMinutes());
        item.setSpiceLevel(req.getSpiceLevel());
        item.setStatus(req.getStatus() == null ? MenuItemStatus.AVAILABLE : req.getStatus());

        int nextSort = itemRepo.maxSortOrder(menuId, category.getId()) + 1;
        item.setSortOrder(nextSort);

        return toResponse(itemRepo.save(item));
    }

    @Override
    @Transactional(readOnly = true)
    public MenuItemResponse get(String itemId) {

        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        return toResponse(item);
    }

    @Override
    @Transactional(readOnly = true)
    public MenuItemResponse getById(String itemId) {
        MenuItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        return toResponse(item);
    }

    @Override
    @Transactional
    public MenuItemResponse update(String itemId, MenuItemUpdateRequest req) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        if (req.getCategoryId() != null) {
            String menuId = item.getMenu().getId();
            Category newCategory = categoryRepo.findByIdAndMenu_Id(req.getCategoryId(), menuId)
                    .orElseThrow(() -> new BadRequestException("categoryId must belong to the same menu"));
            item.setCategory(newCategory);
        }

        if (req.getName() != null) item.setName(req.getName());
        if (req.getDescription() != null) item.setDescription(nullIfBlank(req.getDescription()));
        if (req.getPrice() != null) item.setPrice(money(req.getPrice()));
        if (req.getImageUrl() != null) item.setImageUrl(normalizeUrl(req.getImageUrl()));

        if (req.getIngredients() != null) item.setIngredients(normalizeIngredients(req.getIngredients()));
        if (req.getAllergens() != null) item.setAllergens(normalizeNullableList(req.getAllergens()));
        if (req.getTags() != null) item.setTags(normalizeNullableList(req.getTags()));

        if (req.getCalories() != null) item.setCalories(req.getCalories());
        if (req.getIsFeatured() != null) item.setFeatured(req.getIsFeatured());
        if (req.getPrepTimeMinutes() != null) item.setPrepTimeMinutes(req.getPrepTimeMinutes());
        if (req.getSpiceLevel() != null) item.setSpiceLevel(req.getSpiceLevel());
        if (req.getStatus() != null) item.setStatus(req.getStatus());

        return toResponse(item);
    }

    @Override
    @Transactional
    public MenuItemResponse updateStatus(String itemId, MenuItemStatusUpdateRequest req) {
        String businessId = currentUser.businessId();

        // ✅ security fix: enforce ownership (Node version doesn't)
        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        item.setStatus(req.getStatus());
        return toResponse(item);
    }

    @Override
    @Transactional
    public void delete(String itemId) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        itemRepo.delete(item);
    }

    // ------------------ mapping ------------------
    private MenuItemResponse toResponse(MenuItem i) {
        return MenuItemResponse.builder()
                .id(i.getId())
                .menuId(i.getMenu().getId())
                .categoryId(i.getCategory().getId())
                .name(i.getName())
                .description(i.getDescription())
                .price(i.getPrice())
                .imageUrl(i.getImageUrl())
                .ingredients(i.getIngredients())
                .allergens(i.getAllergens())
                .tags(i.getTags())
                .calories(i.getCalories())
                .isFeatured(i.isFeatured())
                .prepTimeMinutes(i.getPrepTimeMinutes())
                .spiceLevel(i.getSpiceLevel())
                .status(i.getStatus())
                .sortOrder(i.getSortOrder())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .build();
    }

    // ------------------ normalization & validation ------------------

    private BigDecimal money(BigDecimal v) {
        if (v == null) return null;
        if (v.signum() < 0) throw new BadRequestException("price must be nonnegative");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeUrl(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        if (!s.matches("(?i)^https?://.+")) throw new BadRequestException("Invalid url");
        return s;
    }

    private List<String> normalizeIngredients(List<String> ingredients) {
        if (ingredients == null || ingredients.isEmpty())
            throw new BadRequestException("ingredients must have at least 1 item");

        List<String> trimmed = ingredients.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .toList();

        if (trimmed.stream().anyMatch(String::isEmpty)) {
            throw new BadRequestException("ingredients must not contain empty values");
        }

        List<String> lower = trimmed.stream().map(String::toLowerCase).toList();
        if (new HashSet<>(lower).size() != lower.size()) {
            throw new BadRequestException("ingredients must be unique (case-insensitive)");
        }

        return trimmed;
    }

    private List<String> normalizeNullableList(List<String> v) {
        if (v == null) return null;
        return v.stream().filter(Objects::nonNull).map(String::trim).toList();
    }

    private String normalizeSearch(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }

    private String nullIfBlank(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }
}




