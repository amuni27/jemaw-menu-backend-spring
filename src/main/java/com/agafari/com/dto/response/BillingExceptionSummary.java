package com.agafari.com.dto.response;

/**
 * AGF EX-F (register §91, PO F-2): unresolved exceptions for the Super Admin badge. {@code unresolved} counts each record
 * once (open, in review, escalated or automatic handling). {@code open}, {@code escalated}, {@code autoHandling} split it by
 * status; {@code overdue} is a separate view of the open-state records past their deadline (not added to anything).
 */
public record BillingExceptionSummary(long unresolved, long open, long escalated, long autoHandling, long overdue) {
}
