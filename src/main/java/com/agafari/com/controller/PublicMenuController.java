package com.agafari.com.controller;


import com.agafari.com.dto.response.MenuItemResponse;
import com.agafari.com.dto.response.PublicBusinessMenuResponse;
import com.agafari.com.service.MenuItemService;
import com.agafari.com.service.PublicMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/venue")
@RequiredArgsConstructor
public class PublicMenuController {

    private final MenuItemService menuItemService;

    private final PublicMenuService publicMenuService;

    @GetMapping("/{businessId}/menu")
    public ResponseEntity<PublicBusinessMenuResponse> getBusinessMenu(@PathVariable String businessId) {
        return ResponseEntity.ok(publicMenuService.getBusinessMenu(businessId));
    }

    @GetMapping("/items/{itemId}")
    public ResponseEntity<MenuItemResponse> get(@PathVariable String itemId) {
        return ResponseEntity.ok(menuItemService.getById(itemId));
    }
}
