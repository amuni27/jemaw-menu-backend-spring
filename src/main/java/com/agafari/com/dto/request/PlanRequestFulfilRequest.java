package com.agafari.com.dto.request;

import java.time.Instant;

/** AGF E4-M4: the Super Admin fulfils a request by recording the manual subscription for the requested plan. */
public record PlanRequestFulfilRequest(Integer expectedConfigVersion, Instant periodEnd, Integer rowVersion) {
}
