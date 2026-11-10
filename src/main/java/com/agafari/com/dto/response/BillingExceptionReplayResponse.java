package com.agafari.com.dto.response;

/** AGF SUB-R4-11: the exception after a replay, and how the replayed event ended ({@code PaymentOutcomeService.Result}). */
public record BillingExceptionReplayResponse(BillingExceptionView exception, String eventResult) {
}
