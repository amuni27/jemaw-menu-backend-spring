package com.agafari.com.dto.request;


import com.agafari.com.enums.DayOfWeekEnum;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BusinessHourDto {
    @NotNull
    private DayOfWeekEnum dayOfWeek;

    @JsonProperty("isOpen")
    private boolean isOpen;
    // format HH:mm or null
    private String startTime;
    private String endTime;
}

