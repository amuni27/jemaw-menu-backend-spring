package com.agafari.com.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AGF-S2-25: create (POST) or change (PATCH) a special. On PATCH, omitted/null = unchanged; "" clears a note;
 * clearStartsOn / clearEndsOn / clearTimeWindow remove a saved date or the daily window (S2-30 style).
 * Dates and times are strings so a malformed value gets a field error. Rules: SpecialServiceImpl.
 */
@Data
public class SpecialRequest implements ScheduleInput { // AGF-S2-36: shared schedule fields
    private String menuItemId;
    private BigDecimal specialPrice;
    private String note;
    private String noteAm;
    /** ISO date (YYYY-MM-DD), restaurant local date */
    private String startsOn;
    private String endsOn;
    /** MONDAY…SUNDAY; empty = every day */
    private List<String> daysOfWeek;
    /** HH:mm, restaurant local time; both or neither; end after start */
    private String startTime;
    private String endTime;
    private Boolean paused;
    private Boolean clearStartsOn;
    private Boolean clearEndsOn;
    private Boolean clearTimeWindow;
}
