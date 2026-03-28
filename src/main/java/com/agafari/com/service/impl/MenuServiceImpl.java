package com.agafari.com.service.impl;

import com.agafari.com.dto.request.MenuCreateRequest;
import com.agafari.com.dto.request.MenuUpdateRequest;
import com.agafari.com.dto.response.MenuResponse;
import com.agafari.com.dto.response.MenuTypeResponse;
import com.agafari.com.enums.MenuVisibility;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuType;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.jpa.repository.MenuTypeRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.MenuService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuServiceImpl.class);
    private final MenuRepository menuRepo;
    private final BusinessRepository businessRepo;
    private final MenuTypeRepository menuTypeRepo;

    // delete children
    private final CategoryRepository categoryRepo;
    private final MenuItemRepository menuItemRepo;

    private final CurrentUser currentUser;

    @Transactional
    @Override
    public List<MenuResponse> listMyBusinessMenus() {
        String businessId = currentUser.businessId();

        return menuRepo.findByBusiness_IdOrderByCreatedAtDesc(businessId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    @Override
    public MenuResponse createMenu(MenuCreateRequest request) {
        log.info("Menu create starting for {}", request);
        String businessId = currentUser.businessId();

        Business business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found. Cannot create menu."));

        // Node: validate menuType exists using data.menuId (menuTypeId)
        MenuType menuType = menuTypeRepo.findById(request.getMenuTypeId())
                .orElseThrow(() -> new BadRequestException("Invalid menu type for this business."));

        Menu menu = new Menu();
        menu.setId(UUID.randomUUID().toString());
        menu.setBusiness(business);
        menu.setMenuType(menuType);

        menu.setName(request.getName());
        menu.setDescription(request.getDescription());

        menu.setVisibility(request.getVisibility() == null ? MenuVisibility.PUBLIC : request.getVisibility());
        // isActive default true in entity

        return toResponse(menuRepo.save(menu));
    }

    @Transactional
    @Override
    public MenuResponse getMyBusinessMenu(String menuId) {
        String businessId = currentUser.businessId();

        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        return toResponse(menu);
    }

    @Transactional
    @Override
    public MenuResponse patchMenu(String menuId, MenuUpdateRequest request) {
        String businessId = currentUser.businessId();

        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        if (request.getName() != null) menu.setName(request.getName());

        // If you want "nullable" behavior like Node (zod nullable), this is OK:
        // - send description: null => clears description
        // - omit description => unchanged
        if (request.getDescription() != null || (request.getDescription() == null && requestWasProvidedDescription(request))) {
            menu.setDescription(request.getDescription());
        }

        if (request.getIsActive() != null) menu.setActive(request.getIsActive());
        if (request.getVisibility() != null) menu.setVisibility(request.getVisibility());

        return toResponse(menuRepo.save(menu));
    }

    /**
     * Deletes related data then deletes the menu.
     * Since you have ONE QR code for all menus, no QR table delete here.
     */
    @Override
    @Transactional
    public void deleteMenuCascade(String menuId) {
        String businessId = currentUser.businessId();

        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        // delete children by menuId
        menuItemRepo.deleteByMenu_Id(menuId);

        menuRepo.delete(menu);
    }

    @Transactional
    @Override
    public List<MenuTypeResponse> getAllMenuTypes() {
        return menuTypeRepo.findAllByOrderByNameAsc()
                .stream()
                .map(mt -> MenuTypeResponse.builder()
                        .id(mt.getId())
                        .name(mt.getName())
                        .build())
                .toList();
    }

    private MenuResponse toResponse(Menu m) {
        String currency = (m.getBusiness() != null && m.getBusiness().getCurrency() != null)
                ? m.getBusiness().getCurrency()
                : "USD";

        return MenuResponse.builder()
                .id(m.getId())
                .businessId(m.getBusiness().getId())
                .menuTypeId(m.getMenuType().getId())
                .menuTypeName(m.getMenuType().getName())
                .name(m.getName())
                .description(m.getDescription())
                .isActive(m.isActive())
                .visibility(m.getVisibility())
                .currency(currency)
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }

    /**
     * OPTIONAL NOTE:
     * In plain Spring, you can't reliably know if JSON included "description":null
     * unless you use JsonNode or Optional<JsonNullable>.
     *
     * If you don't need "clear description" feature, delete this logic and only:
     * if (request.getDescription() != null) menu.setDescription(request.getDescription());
     */
    private boolean requestWasProvidedDescription(MenuUpdateRequest request) {
        // simplest default: return false => we only update description when non-null
        return false;
    }
}
