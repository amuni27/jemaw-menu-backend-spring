package com.agafari.com.dto.response;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CategoryReorderRequest {

    @NotEmpty
    private List<String> orderedIds;
}

