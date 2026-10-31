package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** AGF-S2-36: an owner's category special with its category and status in the restaurant's time. Owner API only. */
@Data
@Builder
public class CategorySpecialResponse {
    private String id;
    private String categoryId;
    private String categoryName;
    private String menuId;
    private String menuName;
    private int discountPercent;
    private String note;
    private String noteAm;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private List<String> daysOfWeek;
    private String startTime;
    private String endTime;
    private boolean paused;
    /** ACTIVE, SCHEDULED, ENDED or PAUSED */
    private String status;
    /** Dishes guests can see in the category with a price above 0 (owner hint: "No eligible dishes") */
    private int eligibleDishes;
    private Instant createdAt;
    private Instant updatedAt;
}
