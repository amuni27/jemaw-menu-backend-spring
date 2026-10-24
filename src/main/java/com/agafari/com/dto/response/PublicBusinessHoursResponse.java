package com.agafari.com.dto.response;

import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.jpa.entity.BusinessHours;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/** One day of opening hours for guests (AGF-S2-03). No internal IDs. Times are "HH:mm" in the business time zone. */
@Data
@Builder
public class PublicBusinessHoursResponse {

    private DayOfWeekEnum dayOfWeek;

    @JsonProperty("isOpen")
    private boolean isOpen;

    private String startTime;
    private String endTime;

    public static PublicBusinessHoursResponse from(BusinessHours bh) {
        return PublicBusinessHoursResponse.builder()
                .dayOfWeek(bh.getDayOfWeek())
                .isOpen(bh.isOpen())
                .startTime(bh.getStartTime())
                .endTime(bh.getEndTime())
                .build();
    }
}
