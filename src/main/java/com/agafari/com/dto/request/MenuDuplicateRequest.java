package com.agafari.com.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** AGF-S2-18: optional name for the copy; omitted = "{name} (copy)". */
@Data
public class MenuDuplicateRequest {

    @Size(max = 255)
    @Pattern(regexp = "(?s).*\\S.*", message = "Name can't be blank")
    private String name;
}
