package com.agafari.com.dto.response;

import java.util.List;

/** AGF E4-M2: one page of configuration versions, newest first. */
public record MonetizationConfigHistoryPage(List<MonetizationConfigResponse> items, int page, int size, long total) {
}
