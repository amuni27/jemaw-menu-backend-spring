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
import com.agafari.com.service.AmharicText;
import com.agafari.com.service.CurrencyPolicy;
import com.agafari.com.service.SpecialSchedule;
import com.agafari.com.service.TimeZoneRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AGF-S2-25 (checkpoint A): owner CRUD for specials, scoped to the caller's restaurant (another owner's dish or
 * special is "not found", S1-06). One special per dish. The price rule (≥ 0, at most 2 decimals, below the dish's
 * regular price) is checked when a special is created or its price or dish changes; other edits (pause, schedule,
 * note) don't re-check it (see the regular-price recommendation in the S2-25 report).
 */
@Service
@RequiredArgsConstructor
public class SpecialServiceImpl {

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

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

        if (req.getNote() != null) s.setNote(req.getNote().isBlank() ? null : req.getNote().strip());
        if (s.getNote() != null && s.getNote().length() > 255) errors.put("note", "Use at most 255 characters.");
        if (req.getNoteAm() != null) s.setNoteAm(AmharicText.normalize(req.getNoteAm()));
        if (s.getNoteAm() != null && s.getNoteAm().length() > 255) errors.put("noteAm", "Use at most 255 characters.");

        if (Boolean.TRUE.equals(req.getClearStartsOn())) s.setStartsOn(null);
        if (Boolean.TRUE.equals(req.getClearEndsOn())) s.setEndsOn(null);
        if (req.getStartsOn() != null) s.setStartsOn(date(req.getStartsOn(), "startsOn", errors));
        if (req.getEndsOn() != null) s.setEndsOn(date(req.getEndsOn(), "endsOn", errors));
        if (s.getStartsOn() != null && s.getEndsOn() != null && s.getEndsOn().isBefore(s.getStartsOn()) && !errors.containsKey("endsOn")) {
            errors.put("endsOn", "The end date can't be before the start date.");
        }

        if (req.getDaysOfWeek() != null) {
            List<String> days = req.getDaysOfWeek().stream().map(d -> d == null ? "" : d.strip().toUpperCase()).distinct().toList();
            if (!SpecialSchedule.DAYS.containsAll(days)) errors.put("daysOfWeek", "Choose days from Monday to Sunday.");
            else s.setDaysOfWeek(SpecialSchedule.DAYS.stream().filter(days::contains).toList());
        }
        if (s.getDaysOfWeek() == null) s.setDaysOfWeek(List.of());

        if (Boolean.TRUE.equals(req.getClearTimeWindow())) {
            s.setStartTime(null);
            s.setEndTime(null);
        }
        if (req.getStartTime() != null) s.setStartTime(req.getStartTime().strip());
        if (req.getEndTime() != null) s.setEndTime(req.getEndTime().strip());
        timeWindow(s, errors);

        if (req.getPaused() != null) s.setPaused(req.getPaused());

        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    private static String priceProblem(BigDecimal price, BigDecimal regular) {
        if (price.signum() < 0) return "The special price can't be negative.";
        if (price.stripTrailingZeros().scale() > 2) return "Use at most 2 decimals.";
        if (regular != null && price.compareTo(regular) >= 0) return "The special price must be lower than the regular price (" + regular.toPlainString() + ").";
        return null;
    }

    private static LocalDate date(String value, String field, Map<String, String> errors) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            errors.put(field, "Use a date like 2026-11-30.");
            return null;
        }
    }

    private static void timeWindow(Special s, Map<String, String> errors) {
        String start = s.getStartTime();
        String end = s.getEndTime();
        if (start == null && end == null) return;
        if (start != null && !TIME.matcher(start).matches()) errors.put("startTime", "Use a time like 16:00.");
        if (end != null && !TIME.matcher(end).matches()) errors.put("endTime", "Use a time like 18:00.");
        if (errors.containsKey("startTime") || errors.containsKey("endTime")) return;
        if (start == null) errors.put("startTime", "Add a start time too.");
        else if (end == null) errors.put("endTime", "Add an end time too.");
        else if (end.compareTo(start) <= 0) errors.put("endTime", "The end time must be after the start time.");
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
