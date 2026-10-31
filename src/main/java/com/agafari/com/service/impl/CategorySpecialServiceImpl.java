package com.agafari.com.service.impl;

import com.agafari.com.dto.request.CategorySpecialRequest;
import com.agafari.com.dto.response.CategorySpecialResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.Category;
import com.agafari.com.jpa.entity.CategorySpecial;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.CategoryRepository;
import com.agafari.com.jpa.repository.CategorySpecialRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.PublicVisibilityPolicy;
import com.agafari.com.service.ScheduleRules;
import com.agafari.com.service.SpecialSchedule;
import com.agafari.com.service.TimeZoneRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGF-S2-36 (checkpoint A): owner CRUD for category specials, scoped to the caller's restaurant (another owner's
 * category or special is "not found", S1-06). One per category; same schedule rules as dish specials (ScheduleRules).
 * Dish specials are untouched. Guests get category special data in checkpoint B.
 */
@Service
@RequiredArgsConstructor
public class CategorySpecialServiceImpl {

    private final CategorySpecialRepository specials;
    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final BusinessRepository businesses;
    private final CurrentUser currentUser;

    private volatile Clock clock = Clock.systemUTC(); // replaceable in tests

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CategorySpecialResponse> list() {
        Business business = ownBusiness();
        return specials.findByBusiness_IdOrderByCreatedAtDesc(business.getId()).stream().map(s -> toResponse(s, business)).toList();
    }

    @Transactional
    public CategorySpecialResponse create(CategorySpecialRequest req) {
        Business business = ownBusiness();
        if (req.getCategoryId() == null || req.getCategoryId().isBlank()) {
            throw new FieldValidationException(Map.of("categoryId", "Choose a category."));
        }
        CategorySpecial special = new CategorySpecial();
        special.setBusiness(business);
        special.setCategory(ownCategory(req.getCategoryId(), business.getId()));
        apply(special, req, true);
        return toResponse(specials.save(special), business);
    }

    @Transactional
    public CategorySpecialResponse update(String id, CategorySpecialRequest req) {
        String businessId = currentUser.businessId();
        CategorySpecial special = specials.findByIdAndBusiness_Id(id, businessId).orElseThrow(() -> new NotFoundException("Category special not found"));
        boolean categoryChanged = req.getCategoryId() != null && !req.getCategoryId().equals(special.getCategory().getId());
        if (categoryChanged) special.setCategory(ownCategory(req.getCategoryId(), businessId));
        apply(special, req, categoryChanged);
        return toResponse(specials.save(special), special.getBusiness());
    }

    @Transactional
    public void delete(String id) {
        CategorySpecial special = specials.findByIdAndBusiness_Id(id, currentUser.businessId())
                .orElseThrow(() -> new NotFoundException("Category special not found"));
        specials.delete(special);
    }

    private Business ownBusiness() {
        return businesses.findById(currentUser.businessId()).orElseThrow(() -> new NotFoundException("Business not found"));
    }

    private Category ownCategory(String categoryId, String businessId) {
        return categories.findByIdAndMenu_Business_Id(categoryId, businessId).orElseThrow(() -> new NotFoundException("Category not found"));
    }

    private void apply(CategorySpecial s, CategorySpecialRequest req, boolean categoryChangedOrNew) {
        Map<String, String> errors = new LinkedHashMap<>();
        boolean creating = s.getId() == null;

        if (creating || categoryChangedOrNew) {
            specials.findByCategory_Id(s.getCategory().getId())
                    .filter(other -> !other.getId().equals(s.getId()))
                    .ifPresent(other -> errors.put("categoryId", "This category already has a category special."));
        }

        if (req.getDiscountPercent() != null) {
            BigDecimal p = req.getDiscountPercent();
            if (p.stripTrailingZeros().scale() > 0) errors.put("discountPercent", "Use a whole number from 1 to 90.");
            else if (p.compareTo(BigDecimal.ONE) < 0 || p.compareTo(BigDecimal.valueOf(90)) > 0) errors.put("discountPercent", "Use a whole number from 1 to 90.");
            else s.setDiscountPercent(p.intValueExact());
        } else if (creating) {
            errors.put("discountPercent", "Enter the discount percentage.");
        }

        ScheduleRules.apply(s, req, errors);

        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    private CategorySpecialResponse toResponse(CategorySpecial s, Business business) {
        Category category = s.getCategory();
        int eligible = (int) items.findByCategory_IdOrderBySortOrderAscCreatedAtAsc(category.getId()).stream()
                .filter(i -> PublicVisibilityPolicy.PUBLIC_ITEM_STATUSES.contains(i.getStatus()))
                .filter(i -> i.getPrice() != null && i.getPrice().signum() > 0)
                .count();
        SpecialSchedule.Status status = SpecialSchedule.scheduleStatus(s, clock.instant(), TimeZoneRules.zoneId(business.getTimeZone()));
        return CategorySpecialResponse.builder()
                .id(s.getId())
                .categoryId(category.getId())
                .categoryName(category.getName())
                .menuId(category.getMenu().getId())
                .menuName(category.getMenu().getName())
                .discountPercent(s.getDiscountPercent())
                .note(s.getNote())
                .noteAm(s.getNoteAm())
                .startsOn(s.getStartsOn())
                .endsOn(s.getEndsOn())
                .daysOfWeek(s.getDaysOfWeek())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .paused(s.isPaused())
                .status(status.name())
                .eligibleDishes(eligible)
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
