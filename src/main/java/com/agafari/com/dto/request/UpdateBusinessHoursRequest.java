package com.agafari.com.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UpdateBusinessHoursRequest {

    @JsonProperty("open24_7")
    private boolean open24_7;

    @Valid
    @NotNull
    private List<BusinessHourDto> businessHours;
}
