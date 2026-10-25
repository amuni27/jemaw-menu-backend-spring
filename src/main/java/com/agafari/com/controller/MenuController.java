package com.agafari.com.controller;

import com.agafari.com.dto.request.MenuCreateRequest;
import com.agafari.com.dto.request.MenuDuplicateRequest;
import com.agafari.com.dto.request.MenuUpdateRequest;
import com.agafari.com.dto.response.MenuDuplicateResponse;
import com.agafari.com.dto.response.MenuResponse;
import com.agafari.com.dto.response.MenuTypeResponse;
import com.agafari.com.jpa.entity.MenuType;
import com.agafari.com.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /**
     * GET /api/menus
     * Node: prisma.menu.findMany({ where: { businessId }, orderBy: createdAt desc, include: { menuType: true }})
     */
    @GetMapping
    public ResponseEntity<List<MenuResponse>> listMyBusinessMenus() {
        return ResponseEntity.ok(menuService.listMyBusinessMenus());
    }

    /**
     * POST /api/menus
     * Node: validate business exists + menuType exists, then create menu with business connect and menuType connect
     */
    @PostMapping
    public ResponseEntity<MenuResponse> create(@Valid @RequestBody MenuCreateRequest request) {
        MenuResponse created = menuService.createMenu(request);
        return ResponseEntity.status(201).body(created);
    }

    /**
     * GET /api/menus/:menuId
     * Node: findFirst({ where: { id: menuId, businessId }})
     */
    @GetMapping("/{menuId}")
    public ResponseEntity<MenuResponse> getOne(@PathVariable String menuId) {
        return ResponseEntity.ok(menuService.getMyBusinessMenu(menuId));
    }

    /**
     * PATCH /api/menus/:menuId
     * Node: update({ where: { id: req.menu.id }, data })
     * Spring: we enforce ownership inside service (id + businessId).
     */
    @PatchMapping("/{menuId}")
    public ResponseEntity<MenuResponse> patch(@PathVariable String menuId,
                                              @Valid @RequestBody MenuUpdateRequest request) {
        return ResponseEntity.ok(menuService.patchMenu(menuId, request));
    }

    /**
     * DELETE /api/menus/:menuId
     * Node: $transaction deleteMany(qrTable, menuItem, category) then delete menu
     */
    @DeleteMapping("/{menuId}")
    public ResponseEntity<Void> delete(@PathVariable String menuId) {
        menuService.deleteMenuCascade(menuId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/menus/:menuId/duplicate
     * AGF-S2-18: copies the menu with all categories and items into a new, unpublished menu. Body optional: {"name"}.
     */
    @PostMapping("/{menuId}/duplicate")
    public ResponseEntity<MenuDuplicateResponse> duplicate(@PathVariable String menuId,
                                                           @Valid @RequestBody(required = false) MenuDuplicateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menuService.duplicateMenu(menuId, request));
    }

    /**
     * GET /api/menus/type/all
     * Node: prisma.menuType.findMany({ orderBy: name asc })
     */
    @GetMapping("/type/all")
    public ResponseEntity<List<MenuTypeResponse>> getAllMenuTypes() {
        return ResponseEntity.ok(menuService.getAllMenuTypes());
    }
}

