package com.agafari.com.dto.request;

import com.agafari.com.enums.MenuItemStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MenuItemStatusUpdateRequest {
    @NotNull
    private MenuItemStatus status;
}