package com.agafari.com.dto.request;

/**
 * AGF SUB-R4-11: the body of every exception action. {@code rowVersion} is always required; the other fields per action:
 * a note's {@code note}; an escalation's {@code reason} and optional {@code escalatedTo}; a resolution's {@code resolution}
 * ({@code FUNDS_HANDLED_EXTERNALLY} or {@code NO_ACTION_REQUIRED}) and {@code note}.
 */
public record BillingExceptionActionRequest(Integer rowVersion, String note, String reason, String escalatedTo, String resolution) {
}
