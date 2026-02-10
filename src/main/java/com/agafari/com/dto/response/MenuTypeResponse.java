package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class MenuTypeResponse {
    private String id;
    private String name;
    private Instant updatedAt;
}