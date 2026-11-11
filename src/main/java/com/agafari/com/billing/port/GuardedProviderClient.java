package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;
import com.agafari.com.jobs.ProviderCallGuard;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** AGF SUB-R4-04 (BD-21): every provider client extends this, so no method can run inside a transaction. */
public abstract class GuardedProviderClient implements PaymentProviderClient {

    @Override
    public final ProviderCapabilities capabilities() {
        ProviderCallGuard.assertNoTransaction();
        return doCapabilities();
    }

    @Override
    public final ProviderSession createSession(ProviderSessionRequest request, String idempotencyKey) {
        ProviderCallGuard.assertNoTransaction();
        return doCreateSession(request, idempotencyKey);
    }

    @Override
    public final ExpireResult expireSession(String providerSessionRef, String idempotencyKey) {
        ProviderCallGuard.assertNoTransaction();
        return doExpireSession(providerSessionRef, idempotencyKey);
    }

    @Override
    public final VerifiedPayment verifyPayment(String providerRef) {
        ProviderCallGuard.assertNoTransaction();
        return doVerifyPayment(providerRef);
    }

    @Override
    public final RefundSubmission submitRefund(String providerPaymentRef, Money amount, String idempotencyKey, Map<String, String> metadata) {
        ProviderCallGuard.assertNoTransaction();
        return doSubmitRefund(providerPaymentRef, amount, idempotencyKey, metadata);
    }

    @Override
    public final Optional<ProviderRefund> findRefund(String providerPaymentRef, String refundRequestId) {
        ProviderCallGuard.assertNoTransaction();
        return doFindRefund(providerPaymentRef, refundRequestId);
    }

    @Override
    public final List<ProviderRecord> listSince(Instant since, RecordType type) {
        ProviderCallGuard.assertNoTransaction();
        return doListSince(since, type);
    }

    @Override
    public final SignatureVerifier signatureVerifier() {
        ProviderCallGuard.assertNoTransaction();
        return doSignatureVerifier();
    }

    /** AGF SUB-R5-02 (D4). */
    @Override
    public final ProviderSubscriptionState subscriptionOfPayment(String providerPaymentRef) {
        ProviderCallGuard.assertNoTransaction();
        return doSubscriptionOfPayment(providerPaymentRef);
    }

    /** AGF SUB-R5-02 (D4). */
    @Override
    public final SubscriptionCancelResult cancelSubscription(String subscriptionRef) {
        ProviderCallGuard.assertNoTransaction();
        return doCancelSubscription(subscriptionRef);
    }

    /** Not abstract: providers without the capability (the fakes, Chapa) need nothing. */
    protected ProviderSubscriptionState doSubscriptionOfPayment(String providerPaymentRef) {
        throw new UnsupportedOperationException("This provider can't report a payment's subscription");
    }

    protected SubscriptionCancelResult doCancelSubscription(String subscriptionRef) {
        throw new UnsupportedOperationException("This provider can't cancel a subscription");
    }

    protected abstract ProviderCapabilities doCapabilities();

    protected abstract ProviderSession doCreateSession(ProviderSessionRequest request, String idempotencyKey);

    protected abstract ExpireResult doExpireSession(String providerSessionRef, String idempotencyKey);

    protected abstract VerifiedPayment doVerifyPayment(String providerRef);

    protected abstract RefundSubmission doSubmitRefund(String providerPaymentRef, Money amount, String idempotencyKey, Map<String, String> metadata);

    protected abstract Optional<ProviderRefund> doFindRefund(String providerPaymentRef, String refundRequestId);

    protected abstract List<ProviderRecord> doListSince(Instant since, RecordType type);

    protected abstract SignatureVerifier doSignatureVerifier();
}
