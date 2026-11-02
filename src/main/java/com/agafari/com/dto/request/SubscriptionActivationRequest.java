package com.agafari.com.dto.request;

/**
 * AGF Self-Service Phase A: the owner starts a free plan. {@code withdrawPendingRequestId}/{@code ...RowVersion}: the pending
 * plan request the owner explicitly confirmed withdrawing (SS-3); never withdrawn without them. No restaurant here: it comes
 * from the signed-in owner.
 */
public record SubscriptionActivationRequest(String planCode, Integer expectedConfigVersion, String withdrawPendingRequestId,
                                            Integer withdrawPendingRequestRowVersion) {
}
