package com.agafari.com.controller;


import com.agafari.com.dto.request.MenuItemCreateRequest;
import com.agafari.com.dto.request.MenuItemStatusUpdateRequest;
import com.agafari.com.dto.request.MenuItemUpdateRequest;
import com.agafari.com.dto.response.MenuItemResponse;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.service.MenuItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<MenuItemResponse> create(
            @PathVariable String menuId,
            @Valid @RequestBody MenuItemCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menuItemService.create(menuId, request));
    }

    @GetMapping("/items/{itemId}")
    public ResponseEntity<MenuItemResponse> get(@PathVariable String itemId) {
        return ResponseEntity.ok(menuItemService.get(itemId));
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<MenuItemResponse> update(@PathVariable String itemId,
                                                   @RequestBody MenuItemUpdateRequest request) {
        return ResponseEntity.ok(menuItemService.update(itemId, request));
    }

    @PutMapping("/items/{itemId}/status")
    public ResponseEntity<MenuItemResponse> updateStatus(@PathVariable String itemId,
                                                         @Valid @RequestBody MenuItemStatusUpdateRequest request) {
        return ResponseEntity.ok(menuItemService.updateStatus(itemId, request));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable String itemId) {
        menuItemService.delete(itemId);
        return ResponseEntity.noContent().build();
    }
}
