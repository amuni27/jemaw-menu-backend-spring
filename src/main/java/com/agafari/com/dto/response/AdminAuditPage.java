package com.agafari.com.dto.response;

import java.time.Instant;
import java.util.List;

/** AGF E4-M1: one page of audit events, newest first. */
public record AdminAuditPage(List<Item> items, int page, int size, long total) {

    public record Item(String id, Instant occurredAt, String actorLabel, String action, String targetType, String targetId,
                       String outcome, String detail) {
    }
}
