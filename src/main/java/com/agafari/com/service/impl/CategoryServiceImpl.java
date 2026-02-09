package com.agafari.com.service.impl;

import com.agafari.com.dto.request.CategoryCreateRequest;
import com.agafari.com.dto.request.CategoryUpdateRequest;
import com.agafari.com.dto.response.CategoryReorderRequest;
import com.agafari.com.dto.response.CategoryResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepo;
    private final MenuRepository menuRepo;
    private final MenuItemRepository menuItemRepo;
    private final CurrentUser currentUser;

    //---------------------------------------------
    // LIST
    //---------------------------------------------
    @Transactional(readOnly = true)
    public List<CategoryResponse> listByMenu(String menuId) {

        String businessId = currentUser.businessId();

        // Validate ownership
        if (!menuRepo.existsByIdAndBusiness_Id(menuId, businessId)) {
            throw new NotFoundException("Menu not found");
        }

        return categoryRepo
                .findByMenu_IdAndMenu_Business_IdOrderBySortOrderAsc(menuId, businessId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    //---------------------------------------------
    // CREATE
    //---------------------------------------------
    @Transactional
    public CategoryResponse create(String menuId, CategoryCreateRequest request) {

        String businessId = currentUser.businessId();

        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        Category c = new Category();
        c.setMenu(menu);
        c.setName(request.getName());

        if (request.getSortOrder() != null)
            c.setSortOrder(request.getSortOrder());

        if (request.getIsActive() != null)
            c.setActive(request.getIsActive());

        Category saved = categoryRepo.save(c);

        // menu already attached -> safe
        return toResponse(saved);
    }

    //---------------------------------------------
    // PATCH
    //---------------------------------------------
    @Transactional
    public CategoryResponse patch(String categoryId, CategoryUpdateRequest request) {

        String businessId = currentUser.businessId();

        Category category = categoryRepo
                .findByIdAndMenu_Business_Id(categoryId, businessId)
                .orElseThrow(() -> new NotFoundException("Category not found"));

        if (request.getName() != null)
            category.setName(request.getName());

        if (request.getSortOrder() != null)
            category.setSortOrder(request.getSortOrder());

        if (request.getIsActive() != null)
            category.setActive(request.getIsActive());

        return toResponse(category);
    }

    //---------------------------------------------
    // DELETE
    //---------------------------------------------
    @Transactional
    public void delete(String categoryId) {

        String businessId = currentUser.businessId();

        Category category = categoryRepo
                .findByIdAndMenu_Business_Id(categoryId, businessId)
                .orElseThrow(() -> new NotFoundException("Category not found"));

        long itemCount = menuItemRepo.countByCategory_Id(categoryId);

        if (itemCount > 0) {
            throw new BadRequestException("Category has items; delete items first");
        }

        categoryRepo.delete(category);
    }

    //---------------------------------------------
    // REORDER (VERY SENIOR VERSION)
    //---------------------------------------------
    @Transactional
    public void reorder(String menuId, CategoryReorderRequest request) {

        String businessId = currentUser.businessId();

        if (!menuRepo.existsByIdAndBusiness_Id(menuId, businessId)) {
            throw new NotFoundException("Menu not found");
        }

        List<Category> categories =
                categoryRepo.findByIdInAndMenu_IdAndMenu_Business_Id(
                        request.getOrderedIds(),
                        menuId,
                        businessId
                );

        if (categories.size() != request.getOrderedIds().size()) {
            throw new BadRequestException("One or more categories invalid");
        }

        // 🔥 Senior optimization — O(n) instead of O(n²)
        Map<String, Category> map =
                categories.stream().collect(Collectors.toMap(Category::getId, c -> c));

        for (int i = 0; i < request.getOrderedIds().size(); i++) {
            map.get(request.getOrderedIds().get(i)).setSortOrder(i);
        }
    }

    //---------------------------------------------
    private CategoryResponse toResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .menuId(c.getMenu().getId())
                .name(c.getName())
                .sortOrder(c.getSortOrder())
                .isActive(c.isActive())
                .build();
    }
}

