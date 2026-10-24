package com.agafari.com.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** AGF-S2-08: every item of a category, in the new order. */
@Data
public class ItemReorderRequest {

    @NotEmpty
    private List<String> orderedIds;
}
