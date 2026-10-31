package com.agafari.com.service.impl;

import com.agafari.com.dto.request.SpecialRequest;
import com.agafari.com.dto.response.SpecialResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.entity.Special;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.jpa.repository.SpecialRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.CurrencyPolicy;
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
 * AGF-S2-25 (checkpoint A): owner CRUD for specials, scoped to the caller's restaurant (another owner's dish or
 * special is "not found", S1-06). One special per dish. The price rule (≥ 0, at most 2 decimals, below the dish's
 * regular price) is checked when a special is created or its price or dish changes; other edits (pause, schedule,
 * note) don't re-check it (see the regular-price recommendation in the S2-25 report).
 */
@Service
@RequiredArgsConstructor
public class SpecialServiceImpl {

    private final SpecialRepository specials;
    private final MenuItemRepository items;
    private final BusinessRepository businesses;
    private final CurrentUser currentUser;

    private volatile Clock clock = Clock.systemUTC(); // replaceable in tests (as in S2-26)

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SpecialResponse> list() {
        String businessId = currentUser.businessId();
        Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Business not found"));
        return specials.findByBusiness_IdOrderByCreatedAtDesc(businessId).stream().map(s -> toResponse(s, business)).toList();
    }

    @Transactional
    public SpecialResponse create(SpecialRequest req) {
        String businessId = currentUser.businessId();
        Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Business not found"));
        if (req.getMenuItemId() == null || req.getMenuItemId().isBlank()) {
            throw new FieldValidationException(Map.of("menuItemId", "Choose a dish."));
        }
        MenuItem item = ownDish(req.getMenuItemId(), businessId);
        Special special = new Special();
        special.setBusiness(business);
        special.setMenuItem(item);
        apply(special, req, true);
        return toResponse(specials.save(special), business);
    }

    @Transactional
    public SpecialResponse update(String id, SpecialRequest req) {
        String businessId = currentUser.businessId();
        Special special = specials.findByIdAndBusiness_Id(id, businessId).orElseThrow(() -> new NotFoundException("Special not found"));
        boolean dishChanged = req.getMenuItemId() != null && !req.getMenuItemId().equals(special.getMenuItem().getId());
        if (dishChanged) special.setMenuItem(ownDish(req.getMenuItemId(), businessId));
        apply(special, req, dishChanged);
        return toResponse(specials.save(special), special.getBusiness());
    }

    @Transactional
    public void delete(String id) {
        Special special = specials.findByIdAndBusiness_Id(id, currentUser.businessId())
                .orElseThrow(() -> new NotFoundException("Special not found"));
        specials.delete(special);
    }

    private MenuItem ownDish(String itemId, String businessId) {
        return items.findByIdAndMenu_Business_Id(itemId, businessId).orElseThrow(() -> new NotFoundException("Dish not found"));
    }

    /** Merges the request into the special and validates the result; nothing is saved when a rule fails. */
    private void apply(Special s, SpecialRequest req, boolean dishChangedOrNew) {
        Map<String, String> errors = new LinkedHashMap<>();
        boolean creating = s.getId() == null;

        // one special per dish
        if (creating || dishChangedOrNew) {
            specials.findByMenuItem_Id(s.getMenuItem().getId())
                    .filter(other -> !other.getId().equals(s.getId()))
                    .ifPresent(other -> errors.put("menuItemId", "This dish already has a special."));
        }

        // price: checked on create and when the price or the dish changes
        if (req.getSpecialPrice() != null) s.setSpecialPrice(req.getSpecialPrice());
        if (creating && s.getSpecialPrice() == null) {
            errors.put("specialPrice", "Enter the special price.");
        } else if (req.getSpecialPrice() != null || dishChangedOrNew) {
            String problem = priceProblem(s.getSpecialPrice(), s.getMenuItem().getPrice());
            if (problem != null) errors.put("specialPrice", problem);
        }

        ScheduleRules.apply(s, req, errors); // AGF-S2-36: shared with category specials (same rules and messages)

        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    private static String priceProblem(BigDecimal price, BigDecimal regular) {
        if (price.signum() < 0) return "The special price can't be negative.";
        if (price.stripTrailingZeros().scale() > 2) return "Use at most 2 decimals.";
        if (regular != null && price.compareTo(regular) >= 0) return "The special price must be lower than the regular price (" + regular.toPlainString() + ").";
        return null;
    }

    private SpecialResponse toResponse(Special s, Business business) {
        MenuItem item = s.getMenuItem();
        SpecialSchedule.Status status = SpecialSchedule.status(s, clock.instant(), TimeZoneRules.zoneId(business.getTimeZone()));
        return SpecialResponse.builder()
                .id(s.getId())
                .menuItemId(item.getId())
                .itemName(item.getName())
                .menuId(item.getMenu().getId())
                .menuName(item.getMenu().getName())
                .categoryName(item.getCategory() == null ? null : item.getCategory().getName())
                .regularPrice(item.getPrice())
                .specialPrice(s.getSpecialPrice())
                .currency(CurrencyPolicy.displayCurrency(business.getCurrency()))
                .note(s.getNote())
                .noteAm(s.getNoteAm())
                .startsOn(s.getStartsOn())
                .endsOn(s.getEndsOn())
                .daysOfWeek(s.getDaysOfWeek())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .paused(s.isPaused())
                .status(status.name())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
