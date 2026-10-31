package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** AGF-S2-25: an owner's special with its dish and status in the restaurant's local time. Owner API only. */
@Data
@Builder
public class SpecialResponse {
    private String id;
    private String menuItemId;
    private String itemName;
    private String menuId;
    private String menuName;
    private String categoryName;
    private BigDecimal regularPrice;
    private BigDecimal specialPrice;
    private String currency;
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
    private Instant createdAt;
    private Instant updatedAt;
}
