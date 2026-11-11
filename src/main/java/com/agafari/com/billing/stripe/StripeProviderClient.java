package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Money;
import com.agafari.com.billing.port.ExpireResult;
import com.agafari.com.billing.port.GuardedProviderClient;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.ProviderRefund;
import com.agafari.com.billing.port.ProviderSession;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.billing.port.RefundSubmission;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.VerifiedPayment;
import com.stripe.Stripe;
import com.stripe.StripeClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AGF SUB-R5-01 (BD-13): the Stripe adapter skeleton behind {@code PaymentProviderClient}. Every call goes through
 * {@link GuardedProviderClient}, so none can run inside a transaction. SDK types never leave {@code billing.stripe}.
 *
 * <p>Scaffolding only: <b>no operation calls Stripe</b>. Each is implemented by its own story (R5-02 checkout, session
 * expiry, verification and signatures; R5-05 refunds and lookup; R5-06 listing), none of which is authorized yet.
 * <b>Capabilities are all off</b>: no Stripe semantics (idempotency window, refund behaviour, session expiry) has been
 * verified and recorded for this adapter, so nothing automatic may rely on it (BD-25, PO Q0).
 */
public final class StripeProviderClient extends GuardedProviderClient {

    public static final String PROVIDER = "STRIPE";

    private final StripeClient stripe; // constructing it makes no network call; never exposed
    private final StripeCredentials credentials;

    StripeProviderClient(StripeCredentials credentials) {
        this.credentials = credentials;
        this.stripe = new StripeClient(credentials.secretKey());
    }

    /** The Stripe API version this SDK release is pinned to (a plain string; no SDK type escapes). */
    public static String apiVersion() {
        return Stripe.API_VERSION;
    }

    /** The SDK release in use. */
    public static String sdkVersion() {
        return Stripe.VERSION;
    }

    boolean sdkClientReady() {
        return stripe != null;
    }

    @Override
    protected ProviderCapabilities doCapabilities() {
        return ProviderCapabilities.none();
    }

    @Override
    protected ProviderSession doCreateSession(ProviderSessionRequest request, String idempotencyKey) {
        throw notAuthorized("checkout session creation", "SUB-R5-02");
    }

    @Override
    protected ExpireResult doExpireSession(String providerSessionRef, String idempotencyKey) {
        throw notAuthorized("checkout session expiry", "SUB-R5-02");
    }

    @Override
    protected VerifiedPayment doVerifyPayment(String providerRef) {
        throw notAuthorized("payment verification", "SUB-R5-02");
    }

    @Override
    protected RefundSubmission doSubmitRefund(String providerPaymentRef, Money amount, String idempotencyKey, Map<String, String> metadata) {
        throw notAuthorized("refund submission", "SUB-R5-05");
    }

    @Override
    protected Optional<ProviderRefund> doFindRefund(String providerPaymentRef, String refundRequestId) {
        throw notAuthorized("refund lookup", "SUB-R5-05");
    }

    @Override
    protected List<ProviderRecord> doListSince(Instant since, RecordType type) {
        throw notAuthorized("reconciliation listing", "SUB-R5-06");
    }

    @Override
    protected SignatureVerifier doSignatureVerifier() {
        throw notAuthorized("webhook signature verification", "SUB-R5-02");
    }

    private static UnsupportedOperationException notAuthorized(String operation, String story) {
        return new UnsupportedOperationException("Stripe " + operation + " is not implemented: it belongs to " + story + ", which is not authorized");
    }

    @Override
    public String toString() {
        return "StripeProviderClient[provider=" + PROVIDER + ", apiVersion=" + apiVersion() + ", credentials=" + credentials + "]";
    }
}
