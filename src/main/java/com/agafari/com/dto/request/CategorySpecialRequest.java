package com.agafari.com.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AGF-S2-36: create (POST) or change (PATCH) a category special. Same schedule and note fields and PATCH semantics
 * as dish specials (ScheduleInput). The percentage is a whole number from 1 to 90 (D-CS-5).
 */
@Data
public class CategorySpecialRequest implements ScheduleInput {
    private String categoryId;
    /** Whole number 1–90; decimal so that 12.5 gets a field error instead of being truncated */
    private BigDecimal discountPercent;
    private String note;
    private String noteAm;
    private String startsOn;
    private String endsOn;
    private List<String> daysOfWeek;
    private String startTime;
    private String endTime;
    private Boolean paused;
    private Boolean clearStartsOn;
    private Boolean clearEndsOn;
    private Boolean clearTimeWindow;
}
