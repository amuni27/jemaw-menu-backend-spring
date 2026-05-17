package com.agafari.com.dto.response;

import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.jpa.entity.BusinessHours;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BusinessHoursResponse {

    private String id;
    private DayOfWeekEnum dayOfWeek;

    @JsonProperty("isOpen")
    private boolean isOpen;

    private String startTime;
    private String endTime;

    public static BusinessHoursResponse from(BusinessHours bh) {
        return BusinessHoursResponse.builder()
                .id(bh.getId())
                .dayOfWeek(bh.getDayOfWeek())
                .isOpen(bh.isOpen())
                .startTime(bh.getStartTime())
                .endTime(bh.getEndTime())
                .build();
    }
}