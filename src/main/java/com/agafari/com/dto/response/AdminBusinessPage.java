package com.agafari.com.dto.response;

import java.time.Instant;
import java.util.List;

/** AGF E4-M3: read-only restaurant list for the Super Admin. Business data only: no owner email or other personal data. */
public record AdminBusinessPage(List<Item> items, int page, int size, long total) {

    /** AGF E4-M6: {@code grandfathered} = the current subscription's plan isn't enabled by the current configuration. */
    public record Item(String id, String name, String slug, Instant createdAt, String subscriptionStatus, String planCode, boolean grandfathered) {
    }
}
