package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MenuTypeResponse {
    private String id;
    private String name;
}