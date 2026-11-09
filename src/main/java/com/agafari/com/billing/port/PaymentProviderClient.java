package com.agafari.com.billing.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.agafari.com.billing.Money;

/** AGF SUB-R4-04 (DD §6.1): the only place provider types live. Implementations extend {@link GuardedProviderClient}. */
public interface PaymentProviderClient {

    ProviderCapabilities capabilities();

    ProviderSession createSession(ProviderSessionRequest request, String idempotencyKey);

    ExpireResult expireSession(String providerSessionRef, String idempotencyKey);

    /** A re-fetch; the webhook body is never trusted. */
    VerifiedPayment verifyPayment(String providerRef);

    RefundSubmission submitRefund(String providerPaymentRef, Money amount, String idempotencyKey, Map<String, String> metadata);

    /** Confirms presence only: an empty result never justifies a new submission (BD-22). */
    Optional<ProviderRefund> findRefund(String providerPaymentRef, String refundRequestId);

    List<ProviderRecord> listSince(Instant since, RecordType type);

    SignatureVerifier signatureVerifier();
}
