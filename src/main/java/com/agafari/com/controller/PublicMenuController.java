package com.agafari.com.controller;


import com.agafari.com.dto.response.MenuItemPublicResponse;
import com.agafari.com.dto.response.PublicBusinessMenuResponse;
import com.agafari.com.service.PublicMenuService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/venue")
@RequiredArgsConstructor
public class PublicMenuController {

    private final PublicMenuService publicMenuService;

    @GetMapping("/{businessId}/menu")
    public ResponseEntity<PublicBusinessMenuResponse> getBusinessMenu(@PathVariable String businessId) {
        return ResponseEntity.ok(publicMenuService.getBusinessMenu(businessId));
    }

    @GetMapping("/{businessId}/items/{itemId}")
    @Operation(summary = "Guest-visible item of a business (404 if hidden, unknown or another business's)")
    public ResponseEntity<MenuItemPublicResponse> getBusinessItem(@PathVariable String businessId,
                                                                  @PathVariable String itemId) {
        return ResponseEntity.ok(publicMenuService.getPublicItem(businessId, itemId));
    }

    /** Kept for already-loaded clients; use /venue/{businessId}/items/{itemId}. */
    @Deprecated
    @GetMapping("/items/{itemId}")
    @Operation(summary = "Deprecated: use /venue/{businessId}/items/{itemId}", deprecated = true)
    public ResponseEntity<MenuItemPublicResponse> get(@PathVariable String itemId) {
        return ResponseEntity.ok(publicMenuService.getPublicItem(itemId));
    }
}
