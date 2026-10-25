package com.agafari.com.controller;

import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.ItemReorderRequest;
import com.agafari.com.dto.request.MenuItemCreateRequest;
import com.agafari.com.dto.request.MenuItemStatusUpdateRequest;
import com.agafari.com.dto.request.MenuItemUpdateRequest;
import com.agafari.com.dto.response.CreateMenuItemWithUploadResponse;
import com.agafari.com.dto.response.MenuItemResponse;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.service.MenuItemService;
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
public class MenuItemController {

    private final MenuItemService menuItemService;

    @GetMapping("/menus/{menuId}/items")
    public ResponseEntity<List<MenuItemResponse>> list(
            @PathVariable String menuId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) MenuItemStatus status,
            @RequestParam(required = false) String search
    ) {
        return ResponseEntity.ok(menuItemService.list(menuId, categoryId, status, search));
    }

    @PostMapping("/menus/{menuId}/items")
    public ResponseEntity<CreateMenuItemWithUploadResponse> create(
            @PathVariable String menuId,
            @Valid @RequestBody MenuItemCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(menuItemService.createWithImageUpload(menuId, request));
    }

    /**
     * ✅ confirm the image upload (frontend calls after PUT to R2 succeeds)
     */
    @PostMapping("/items/{itemId}/image/confirm")
    public ResponseEntity<MenuItemResponse> confirmImage(
            @PathVariable String itemId,
            @Valid @RequestBody ConfirmImageUploadRequest req
    ) {
        return ResponseEntity.ok(menuItemService.confirmImageUpload(itemId, req));
    }

    /**
     * ✅ optional: regenerate upload URL (useful for retry)
     */
    @GetMapping("/items/{itemId}/image/presign")
    public ResponseEntity<CreateMenuItemWithUploadResponse> presignImageUpload(
            @PathVariable String itemId,
            @RequestParam String contentType // AGF-S1-17: JPEG, PNG or WebP; signed into the upload URL
    ) {
        return ResponseEntity.ok(menuItemService.presignImageUpload(itemId, contentType));
    }

    @GetMapping("/items/{itemId}")
    public ResponseEntity<MenuItemResponse> get(@PathVariable String itemId) {
        return ResponseEntity.ok(menuItemService.get(itemId));
    }

    @PatchMapping("/items/{itemId}")
    public ResponseEntity<MenuItemResponse> update(
            @PathVariable String itemId,
            @Valid @RequestBody MenuItemUpdateRequest request
    ) {
        return ResponseEntity.ok(menuItemService.update(itemId, request));
    }

    @PutMapping("/items/{itemId}/status")
    public ResponseEntity<MenuItemResponse> updateStatus(
            @PathVariable String itemId,
            @Valid @RequestBody MenuItemStatusUpdateRequest request
    ) {
        return ResponseEntity.ok(menuItemService.updateStatus(itemId, request));
    }

    // AGF-S2-19: one transactional bulk update for the menu; returns {"updated": n}
    @PostMapping("/menus/{menuId}/items/mark-all-available")
    public Map<String, Integer> markAllAvailable(@PathVariable String menuId) {
        return Map.of("updated", menuItemService.markAllAvailable(menuId));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable String itemId) {
        menuItemService.delete(itemId);
        return ResponseEntity.noContent().build();
    }

    // AGF-S2-08: mirrors POST /menus/{menuId}/categories/reorder
    @PostMapping("/categories/{categoryId}/items/reorder")
    public Map<String, Boolean> reorder(
            @PathVariable String categoryId,
            @Valid @RequestBody ItemReorderRequest request
    ) {
        menuItemService.reorder(categoryId, request);
        return Map.of("success", true);
    }
}
