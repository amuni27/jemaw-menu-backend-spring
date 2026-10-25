package com.agafari.com.service.impl;

import com.agafari.com.dto.request.MenuCreateRequest;
import com.agafari.com.dto.request.MenuUpdateRequest;
import com.agafari.com.dto.request.MenuDuplicateRequest;
import com.agafari.com.dto.response.MenuDuplicateResponse;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.service.ImagePolicy;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import java.util.ArrayList;
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
import com.agafari.com.service.CurrencyPolicy;
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

    // AGF-S2-18: copies item images when a menu is duplicated
    private final S3Client s3Client;

    @Value("${cloudflare.bucket}")
    private String bucket;

    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

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
        String businessId = currentUser.businessId();
        log.info("event=menu.create.start businessId={} menuTypeId={}", businessId, request.getMenuTypeId());

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
        menu.setDescription(blankToNull(request.getDescription()));

        menu.setVisibility(request.getVisibility() == null ? MenuVisibility.PUBLIC : request.getVisibility());
        // AGF-S1-15 / PO-2: new menus start unpublished unless the client explicitly publishes them.
        // Existing rows are not touched.
        menu.setActive(Boolean.TRUE.equals(request.getIsActive()));

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

        // AGF-S1-14: omitted (or null) description = unchanged; "" (or blank) clears it
        if (request.getDescription() != null) {
            menu.setDescription(blankToNull(request.getDescription()));
        }

        // AGF-S1-14: the menu type can be changed; it must exist (types are global, not tenant-owned)
        if (request.getMenuTypeId() != null) {
            MenuType menuType = menuTypeRepo.findById(request.getMenuTypeId())
                    .orElseThrow(() -> new BadRequestException("Invalid menu type"));
            menu.setMenuType(menuType);
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

    private static final String COPY_SUFFIX = " (copy)";
    private static final int MAX_NAME = 255;

    /**
     * AGF-S2-18: one transaction. The copy starts unpublished (PO-2) with the source's description, type and
     * visibility; every category and item is copied with its order (D-S2-18-1). Sold-out items become available,
     * hidden items stay hidden (D-S2-18-4). Images are copied to the new item's own key; a failed storage copy leaves
     * that item without an image instead of failing the duplicate (D-S2-18-5).
     */
    @Transactional
    @Override
    public MenuDuplicateResponse duplicateMenu(String menuId, MenuDuplicateRequest request) {
        String businessId = currentUser.businessId();

        Menu source = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        Menu copy = new Menu();
        copy.setId(UUID.randomUUID().toString());
        copy.setBusiness(source.getBusiness());
        copy.setMenuType(source.getMenuType());
        copy.setName(copyName(source.getName(), request == null ? null : request.getName()));
        copy.setDescription(source.getDescription());
        copy.setVisibility(source.getVisibility());
        copy.setActive(false);
        copy = menuRepo.save(copy);

        int categoriesCopied = 0;
        int itemsCopied = 0;
        int imagesNotCopied = 0;

        for (Category category : categoryRepo.findByMenu_IdAndMenu_Business_IdOrderBySortOrderAscCreatedAtAsc(menuId, businessId)) {
            Category newCategory = new Category();
            newCategory.setMenu(copy);
            newCategory.setName(category.getName());
            newCategory.setSortOrder(category.getSortOrder());
            newCategory.setActive(category.isActive());
            newCategory = categoryRepo.save(newCategory);
            categoriesCopied++;

            for (MenuItem item : menuItemRepo.findByCategory_IdOrderBySortOrderAscCreatedAtAsc(category.getId())) {
                MenuItem newItem = copyItem(item, copy, newCategory);
                newItem = menuItemRepo.save(newItem); // the id names the image key
                itemsCopied++;

                if (item.getImageUrl() != null && !copyImage(item.getImageUrl(), newItem, copy.getId())) {
                    imagesNotCopied++;
                }
            }
        }

        log.info("event=menu.duplicate.done businessId={} sourceMenuId={} menuId={} categories={} items={} imagesNotCopied={}",
                businessId, menuId, copy.getId(), categoriesCopied, itemsCopied, imagesNotCopied);

        return MenuDuplicateResponse.builder()
                .menu(toResponse(copy))
                .categoriesCopied(categoriesCopied)
                .itemsCopied(itemsCopied)
                .imagesNotCopied(imagesNotCopied)
                .build();
    }

    /** The requested name (trimmed), or "{name} (copy)" shortened to the 255-character limit. */
    private static String copyName(String sourceName, String requested) {
        if (requested != null) return requested.trim();
        int room = MAX_NAME - COPY_SUFFIX.length();
        String base = sourceName.length() > room ? sourceName.substring(0, room) : sourceName;
        return base + COPY_SUFFIX;
    }

    private static MenuItem copyItem(MenuItem item, Menu menu, Category category) {
        MenuItem copy = new MenuItem();
        copy.setMenu(menu);
        copy.setCategory(category);
        copy.setName(item.getName());
        copy.setDescription(item.getDescription());
        copy.setPrice(item.getPrice());
        copy.setCalories(item.getCalories());
        copy.setIngredients(item.getIngredients() == null ? null : new ArrayList<>(item.getIngredients()));
        copy.setAllergens(new ArrayList<>(item.getAllergens()));
        copy.setTags(new ArrayList<>(item.getTags()));
        // sold out is today's state of the source menu; hidden is an owner choice and is kept
        copy.setStatus(item.getStatus() == MenuItemStatus.OUT_OF_STOCK ? MenuItemStatus.AVAILABLE : item.getStatus());
        copy.setFeatured(item.isFeatured());
        copy.setPrepTimeMinutes(item.getPrepTimeMinutes());
        copy.setSpiceLevel(item.getSpiceLevel());
        copy.setSortOrder(item.getSortOrder());
        return copy;
    }

    /**
     * Copies the source image into the new item's own prefix, keeping the file name, and points the new item at it.
     * Returns false (item stays without an image) if storage refuses the copy. Keys are never logged (S1-16).
     */
    private boolean copyImage(String sourceImage, MenuItem newItem, String menuId) {
        String sourceKey = sourceImage.trim();
        if (sourceKey.startsWith("http")) {
            String base = publicBaseUrl.replaceAll("/$", "") + "/";
            if (!sourceKey.startsWith(base)) {
                newItem.setImageUrl(sourceKey); // not in our storage: nothing to copy, keep the reference
                return true;
            }
            sourceKey = sourceKey.substring(base.length());
        }
        sourceKey = sourceKey.replaceAll("^/+", "");

        String fileName = sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
        String targetKey = ImagePolicy.itemPrefix(newItem.getId()) + "/" + fileName;
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(sourceKey)
                    .destinationBucket(bucket).destinationKey(targetKey)
                    .build());
        } catch (SdkException e) {
            log.warn("event=menu.duplicate.image_copy_failed menuId={} errorClass={}", menuId, e.getClass().getSimpleName());
            return false;
        }
        newItem.setImageUrl(targetKey);
        return true;
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
        // AGF-S2-02: the restaurant's currency; ETB (the entity default) when missing, never USD
        String currency = CurrencyPolicy.displayCurrency(m.getBusiness() != null ? m.getBusiness().getCurrency() : null);

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
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
