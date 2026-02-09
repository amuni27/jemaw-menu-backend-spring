package com.agafari.com.controller;

import com.agafari.com.dto.request.CategoryCreateRequest;
import com.agafari.com.dto.request.CategoryUpdateRequest;
import com.agafari.com.dto.response.CategoryReorderRequest;
import com.agafari.com.dto.response.CategoryResponse;
import com.agafari.com.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/menus/{menuId}/categories")
    public List<CategoryResponse> list(@PathVariable String menuId) {
        return categoryService.listByMenu(menuId);
    }

    @PostMapping("/menus/{menuId}/categories")
    public ResponseEntity<CategoryResponse> create(
            @PathVariable String menuId,
            @Valid @RequestBody CategoryCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(categoryService.create(menuId, request));
    }

    @PatchMapping("/categories/{categoryId}")
    public CategoryResponse patch(
            @PathVariable String categoryId,
            @RequestBody CategoryUpdateRequest request) {

        return categoryService.patch(categoryId, request);
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> delete(@PathVariable String categoryId) {
        categoryService.delete(categoryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/menus/{menuId}/categories/reorder")
    public Map<String, Boolean> reorder(
            @PathVariable String menuId,
            @RequestBody CategoryReorderRequest request) {

        categoryService.reorder(menuId, request);
        return Map.of("success", true);
    }
}
