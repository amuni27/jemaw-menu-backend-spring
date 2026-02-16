package com.agafari.com.service.impl;

import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.MenuItemCreateRequest;
import com.agafari.com.dto.request.MenuItemStatusUpdateRequest;
import com.agafari.com.dto.request.MenuItemUpdateRequest;
import com.agafari.com.dto.response.CreateMenuItemWithUploadResponse;
import com.agafari.com.dto.response.MenuItemResponse;
import com.agafari.com.dto.response.UploadInfo;
import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.MenuItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuItemServiceImpl implements MenuItemService {

    private final CurrentUser currentUser;
    private final MenuRepository menuRepo;
    private final CategoryRepository categoryRepo;
    private final MenuItemRepository itemRepo;

    // confirm check
    private final S3Client s3Client;

    // presign
    private final S3Presigner r2Presigner;

    @Value("${cloudflare.bucket}")
    private String bucket;

    /**
     * Example: https://pub-xxxx.r2.dev  OR  https://cdn.yourdomain.com
     * Must be publicly readable if you want to render images without presigned GET.
     */
    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

    // ---------------- PUBLIC LIST ----------------

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(String menuId, String categoryId, MenuItemStatus status, String search) {
        String businessId = currentUser.businessId();

        if (!menuRepo.existsByIdAndBusiness_Id(menuId, businessId)) {
            throw new NotFoundException("Menu not found");
        }

        return itemRepo.searchMenuItems(menuId, businessId, categoryId, status, normalizeSearch(search))
                .stream()
                .map(this::toResponsePublicUrl)
                .toList();
    }

    // ---------------- CREATE ----------------

    @Override
    @Transactional
    public CreateMenuItemWithUploadResponse createWithImageUpload(String menuId, MenuItemCreateRequest req) {
        String businessId = currentUser.businessId();

        Menu menu = menuRepo.findByIdAndBusiness_Id(menuId, businessId)
                .orElseThrow(() -> new NotFoundException("Menu not found"));

        Category category = categoryRepo
                .findByIdAndMenu_IdAndMenu_Business_Id(req.getCategoryId(), menuId, businessId)
                .orElseThrow(() -> new BadRequestException("categoryId must belong to the menu"));

        List<String> ingredients = normalizeIngredients(req.getIngredients());

        MenuItem item = new MenuItem();
        item.setMenu(menu);
        item.setCategory(category);
        item.setName(req.getName());
        item.setDescription(nullIfBlank(req.getDescription()));
        item.setPrice(money(req.getPrice()));

        // IMPORTANT: always store ONLY objectKey in DB (not full URL)
        item.setImageUrl(null);

        item.setIngredients(ingredients);
        item.setAllergens(normalizeNullableList(req.getAllergens()) == null ? List.of() : normalizeNullableList(req.getAllergens()));
        item.setTags(normalizeNullableList(req.getTags()) == null ? List.of() : normalizeNullableList(req.getTags()));
        item.setCalories(req.getCalories());
        item.setFeatured(Boolean.TRUE.equals(req.getIsFeatured()));
        item.setPrepTimeMinutes(req.getPrepTimeMinutes());
        item.setSpiceLevel(req.getSpiceLevel());
        item.setStatus(req.getStatus() == null ? MenuItemStatus.AVAILABLE : req.getStatus());

        int nextSort = itemRepo.maxSortOrder(menuId, category.getId()) + 1;
        item.setSortOrder(nextSort);

        item = itemRepo.save(item);

        // no image meta => return item only
        if (req.getImage() == null || req.getImage().getContentType() == null) {
            return CreateMenuItemWithUploadResponse.builder()
                    .item(toResponsePublicUrl(item))
                    .upload(null)
                    .build();
        }

        // stable key
        String ext = guessExt(req.getImage().getFileName(), req.getImage().getContentType());
        String objectKey = "agafari/menu-items/" + item.getId() + "/main" + ext;

        item.setImageUrl(objectKey);          // store key
        item = itemRepo.save(item);

        UploadInfo upload = presignPut(objectKey);

        return CreateMenuItemWithUploadResponse.builder()
                .item(toResponsePublicUrl(item))
                .upload(upload)
                .build();
    }

    // ---------------- PRESIGN (RETRY / EDIT) ----------------

    @Override
    @Transactional
    public CreateMenuItemWithUploadResponse presignImageUpload(String itemId) {
        log.info("presign image upload {}", itemId);
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        // IMPORTANT: imageUrl in DB must be a KEY.
        // If DB accidentally contains a full URL, strip it to key (fixes broken keys).
        String key = toObjectKey(item.getImageUrl());

        if (key == null) {
            key = "agafari/menu-items/" + item.getId() + "/main.jpg";
        }

        // persist key only
        if (!Objects.equals(item.getImageUrl(), key)) {
            item.setImageUrl(key);
            item = itemRepo.save(item);
        }

        UploadInfo upload = presignPut(key);

        return CreateMenuItemWithUploadResponse.builder()
                .item(toResponsePublicUrl(item))
                .upload(upload)
                .build();
    }

    // ---------------- CONFIRM ----------------

    @Override
    @Transactional
    public MenuItemResponse confirmImageUpload(String itemId, ConfirmImageUploadRequest req) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        String key = toObjectKey(item.getImageUrl());
        if (key == null) throw new BadRequestException("Item has no image objectKey");

        String reqKey = toObjectKey(req.getObjectKey());
        if (reqKey == null) throw new BadRequestException("objectKey is required");

        if (!key.equals(reqKey)) {
            throw new BadRequestException("objectKey does not match item image key");
        }

        // verify object exists
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(reqKey)
                    .build());
        } catch (Exception e) {
            throw new BadRequestException("Image not found in storage (upload not completed)");
        }

        return toResponsePublicUrl(item);
    }

    // ---------------- GET ----------------

    @Override
    @Transactional(readOnly = true)
    public MenuItemResponse get(String itemId) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        return toResponsePublicUrl(item);
    }

    @Override
    @Transactional(readOnly = true)
    public MenuItemResponse getById(String itemId) {
        MenuItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found"));
        return toResponsePublicUrl(item);
    }

    // ---------------- UPDATE ----------------

    @Override
    @Transactional
    public MenuItemResponse update(String itemId, MenuItemUpdateRequest req) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        if (req.getCategoryId() != null) {
            String menuId = item.getMenu().getId();
            Category newCategory = categoryRepo.findByIdAndMenu_Id(req.getCategoryId(), menuId)
                    .orElseThrow(() -> new BadRequestException("categoryId must belong to the same menu"));
            item.setCategory(newCategory);
        }

        if (req.getName() != null) item.setName(req.getName());
        if (req.getDescription() != null) item.setDescription(nullIfBlank(req.getDescription()));
        if (req.getPrice() != null) item.setPrice(money(req.getPrice()));

        // block manual URL updates
        if (req.getImageUrl() != null) {
            throw new BadRequestException("imageUrl cannot be set directly. Use image upload endpoints.");
        }

        if (req.getIngredients() != null) item.setIngredients(normalizeIngredients(req.getIngredients()));
        if (req.getAllergens() != null) item.setAllergens(normalizeNullableList(req.getAllergens()));
        if (req.getTags() != null) item.setTags(normalizeNullableList(req.getTags()));

        if (req.getCalories() != null) item.setCalories(req.getCalories());
        if (req.getIsFeatured() != null) item.setFeatured(req.getIsFeatured());
        if (req.getPrepTimeMinutes() != null) item.setPrepTimeMinutes(req.getPrepTimeMinutes());
        if (req.getSpiceLevel() != null) item.setSpiceLevel(req.getSpiceLevel());
        if (req.getStatus() != null) item.setStatus(req.getStatus());

        item = itemRepo.save(item);
        return toResponsePublicUrl(item);
    }

    @Override
    @Transactional
    public MenuItemResponse updateStatus(String itemId, MenuItemStatusUpdateRequest req) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        item.setStatus(req.getStatus());
        item = itemRepo.save(item);

        return toResponsePublicUrl(item);
    }

    @Override
    @Transactional
    public void delete(String itemId) {
        String businessId = currentUser.businessId();

        MenuItem item = itemRepo.findByIdAndMenu_Business_Id(itemId, businessId)
                .orElseThrow(() -> new NotFoundException("Item not found"));

        itemRepo.delete(item);
    }

    // ---------------- PRESIGN HELPERS ----------------

    private UploadInfo presignPut(String objectKey) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(put)
                .build();

        String url = r2Presigner.presignPutObject(presignReq).url().toString();

        return UploadInfo.builder()
                .objectKey(objectKey)
                .uploadUrl(url)
                .expiresInSeconds(300)
                .build();
    }

    /**
     * Ensures DB stores ONLY objectKey.
     * - If full URL exists, strip publicBaseUrl prefix.
     * - If starts with "/", remove it.
     */
    private String toObjectKey(String imageUrlOrKey) {
        if (imageUrlOrKey == null) return null;

        String s = imageUrlOrKey.trim();
        if (s.isEmpty()) return null;

        if (s.startsWith("http")) {
            String base = publicBaseUrl.replaceAll("/$", "") + "/";
            if (s.startsWith(base)) {
                return s.substring(base.length()).replaceAll("^/+", "");
            }
            // if it's some other URL, we refuse because it will break presign & URLs
            throw new BadRequestException("imageUrl must be an object key, not a full URL");
        }

        return s.replaceAll("^/+", "");
    }

    // ---------------- RESPONSE MAPPING ----------------

    private MenuItemResponse toResponsePublicUrl(MenuItem i) {
        MenuItemResponse r = toResponse(i);

        if (r.getImageUrl() != null && !r.getImageUrl().startsWith("http")) {
            r.setImageUrl(
                    publicBaseUrl.replaceAll("/$", "") + "/" +
                            r.getImageUrl().replaceAll("^/", "")
            );
        }

        return r;
    }

    private MenuItemResponse toResponse(MenuItem i) {
        return MenuItemResponse.builder()
                .id(i.getId())
                .menuId(i.getMenu().getId())
                .categoryId(i.getCategory().getId())
                .name(i.getName())
                .description(i.getDescription())
                .price(i.getPrice())
                // IMPORTANT: DB stores key; this mapper converts to full public URL
                .imageUrl(i.getImageUrl())
                .ingredients(i.getIngredients())
                .allergens(i.getAllergens())
                .tags(i.getTags())
                .calories(i.getCalories())
                .isFeatured(i.isFeatured())
                .prepTimeMinutes(i.getPrepTimeMinutes())
                .spiceLevel(i.getSpiceLevel())
                .status(i.getStatus())
                .sortOrder(i.getSortOrder())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .build();
    }

    // ---------------- VALIDATION HELPERS ----------------

    private BigDecimal money(BigDecimal v) {
        if (v == null) return null;
        if (v.signum() < 0) throw new BadRequestException("price must be nonnegative");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> normalizeIngredients(List<String> ingredients) {
        if (ingredients == null || ingredients.isEmpty())
            throw new BadRequestException("ingredients must have at least 1 item");

        List<String> trimmed = ingredients.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .toList();

        if (trimmed.stream().anyMatch(String::isEmpty)) {
            throw new BadRequestException("ingredients must not contain empty values");
        }

        List<String> lower = trimmed.stream().map(String::toLowerCase).toList();
        if (new HashSet<>(lower).size() != lower.size()) {
            throw new BadRequestException("ingredients must be unique (case-insensitive)");
        }

        return new ArrayList<>(trimmed);
    }

    private List<String> normalizeNullableList(List<String> v) {
        if (v == null) return null;
        return v.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String normalizeSearch(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }

    private String nullIfBlank(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }

    private String guessExt(String fileName, String contentType) {
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(fileName.lastIndexOf("."));
        }
        if ("image/png".equalsIgnoreCase(contentType)) return ".png";
        if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
        return ".jpg";
    }
}
