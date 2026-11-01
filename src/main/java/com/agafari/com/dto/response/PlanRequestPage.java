package com.agafari.com.dto.response;

import java.util.List;

/** AGF E4-M4: one page of plan requests, newest first. */
public record PlanRequestPage(List<PlanRequestResponse> items, int page, int size, long total) {
}
