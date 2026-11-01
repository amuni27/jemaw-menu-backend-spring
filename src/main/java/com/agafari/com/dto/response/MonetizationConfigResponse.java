package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** AGF E4-M2: the current configuration; only {@code configured=false} before the first save (D-M2-1). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonetizationConfigResponse(boolean configured, Integer version, String setting, List<String> models,
                                         Instant effectiveFrom, Instant changedAt, String changedBy) {

    public static MonetizationConfigResponse notConfigured() {
        return new MonetizationConfigResponse(false, null, null, null, null, null, null);
    }
}
