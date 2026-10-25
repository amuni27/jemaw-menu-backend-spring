package com.agafari.com.controller;

import com.agafari.com.dto.response.PublicBusinessMenuResponse;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.PublicMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF-S2-17: owner-only "view as guest" preview of one of the owner's menus, whatever its publish state or
 * visibility. Authenticated like every /api route; the menu must belong to the signed-in owner's business.
 * The public guest endpoints are unaffected.
 */
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuPreviewController {

    private final PublicMenuService publicMenuService;
    private final CurrentUser currentUser;

    @GetMapping("/{menuId}/preview")
    public ResponseEntity<PublicBusinessMenuResponse> preview(@PathVariable String menuId) {
        return ResponseEntity.ok(publicMenuService.getOwnerPreview(currentUser.businessId(), menuId));
    }
}
