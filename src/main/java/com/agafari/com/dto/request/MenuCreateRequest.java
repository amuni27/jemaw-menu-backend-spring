package com.agafari.com.dto.request;

import com.agafari.com.enums.MenuVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MenuCreateRequest {

    @NotNull
    private String menuTypeId; // menuTypeId

    @NotBlank
    @Size(max = 255)
    private String name;

    private String description;

    private MenuVisibility visibility = MenuVisibility.PUBLIC;
}

