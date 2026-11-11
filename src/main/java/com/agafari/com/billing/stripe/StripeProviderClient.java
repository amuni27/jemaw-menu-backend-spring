package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Currency;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.PaymentMetadata;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.billing.port.ExpireResult;
import com.agafari.com.billing.port.GuardedProviderClient;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.ProviderRefund;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSession;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.billing.port.RefundSubmission;
import com.agafari.com.billing.port.RotatingSignatureVerifier;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.SubscriptionCancelResult;
import com.agafari.com.billing.port.VerifiedPayment;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.InvoicePayment;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.net.HttpClient;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionListParams;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AGF SUB-R5-01/R5-02 (BD-13): the Stripe adapter behind {@code PaymentProviderClient}. Every call goes through
 * {@link GuardedProviderClient}, so none can run inside a transaction; SDK types never leave {@code billing.stripe}.
 *
 * <p>R5-02 implements Agafari-initiated initial checkout only: session creation and expiry, webhook signatures, the
 * re-fetch of a first invoice (PO U1, D6) and the subscription re-fetch and immediate cancellation behind D4. Refunds
 * (R5-05) and listing (R5-06) still refuse. Renewals and plan changes on an existing Stripe subscription are R5-03/R5-04.
 *
 * <p><b>Capabilities</b> (each from Stripe's API reference for API {@code 2026-10-28.endive}, recorded in the R5-02 report):
 * <ul>
 *   <li>{@code idempotentCreate}, window 24 h: POST requests accept idempotency keys, which may be pruned once at least
 *   24 hours old;</li>
 *   <li>{@code sessionExpire}: {@code POST /v1/checkout/sessions/{id}/expire};</li>
 *   <li>{@code cancelSubscription}: {@code DELETE /v1/subscriptions/{id}} cancels immediately ({@code invoice_now} and
 *   {@code prorate} false), idempotent by definition;</li>
 *   <li>{@code idempotentRefund}, {@code refundLookup}: <b>off</b> until R5-05.</li>
 * </ul>
 */
public final class StripeProviderClient extends GuardedProviderClient {

    public static final String PROVIDER = "STRIPE";
    static final Duration MIN_SESSION_LIFETIME = Duration.ofMinutes(30); // Stripe: expires_at 30 min .. 24 h from creation
    static final Duration MAX_SESSION_LIFETIME = Duration.ofHours(24);
    static final String META_CHECKOUT = PaymentMetadata.CHECKOUT_ID;
    static final String META_BUSINESS = PaymentMetadata.BUSINESS_ID;
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(true, true, false, Duration.ofHours(24), false, true);

    private final StripeClient stripe; // never exposed
    private final StripeCredentials credentials;
    private final StripeSettings settings;
    private final Clock clock;
    private final SignatureVerifier verifier;

    StripeProviderClient(StripeCredentials credentials) {
        this(credentials, new StripeSettings(null, null, null), null, Clock.systemUTC());
    }

    /** @param http null for Stripe's own HTTP client; tests pass a stub so no network call is ever made */
    StripeProviderClient(StripeCredentials credentials, StripeSettings settings, HttpClient http, Clock clock) {
        this.credentials = credentials;
        this.settings = settings;
        this.clock = clock;
        StripeClient.StripeClientBuilder builder = StripeClient.builder().setApiKey(credentials.secretKey()).setMaxNetworkRetries(0) // the job runner retries
                .setConnectTimeout(10_000).setReadTimeout(30_000);
        if (http != null) builder.setHttpClient(http);
        this.stripe = builder.build();
        SignatureVerifier previous = credentials.previousWebhookSecret().map(s -> (SignatureVerifier) new StripeSignatureVerifier(s, clock)).orElse(null);
        this.verifier = new RotatingSignatureVerifier(new StripeSignatureVerifier(credentials.webhookSecret(), clock), previous);
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

    StripeSettings settings() {
        return settings;
    }

    @Override
    protected ProviderCapabilities doCapabilities() {
        return CAPABILITIES;
    }

    // ---- checkout sessions ----

    @Override
    protected ProviderSession doCreateSession(ProviderSessionRequest r, String idempotencyKey) {
        String misconfigured = settings.problem();
        if (misconfigured != null) throw new ProviderRejectedException("Stripe checkout is not configured: " + misconfigured);
        if (r.providerPriceRef() == null || r.providerPriceRef().isBlank() || r.interval() == null) throw new ProviderRejectedException("The quoted price has no Stripe price");
        if (r.providerCustomerRef() != null) throw new ProviderRejectedException("A stored customer is never reused for an initial checkout (PO U5)");
        String checkout = r.metadata().get(META_CHECKOUT), business = r.metadata().get(META_BUSINESS);
        if (!r.checkoutId().equals(checkout) || !r.businessId().equals(business)) throw new ProviderRejectedException("The checkout metadata doesn't match the checkout");
        Instant now = clock.instant();
        if (r.expiresAt().isBefore(now.plus(MIN_SESSION_LIFETIME)) || r.expiresAt().isAfter(now.plus(MAX_SESSION_LIFETIME))) {
            throw new ProviderRejectedException("The checkout's remaining lifetime is outside Stripe's 30 minute to 24 hour window");
        }
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(r.providerPriceRef()).setQuantity(1L).build())
                .setSuccessUrl(settings.successUrl())
                .setCancelUrl(settings.cancelUrl())
                .setClientReferenceId(r.checkoutId())
                .putMetadata(META_CHECKOUT, r.checkoutId())
                .putMetadata(META_BUSINESS, r.businessId())
                // PO F2: the subscription (and so every future invoice) never carries the checkout identity
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder().putMetadata(META_BUSINESS, r.businessId()).build())
                .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD) // PO D2
                .setAllowPromotionCodes(false) // PO Q4: the total equals the Agafari price
                .setAutomaticTax(SessionCreateParams.AutomaticTax.builder().setEnabled(false).build()) // PO Q4, AD-16 open
                .setExpiresAt(r.expiresAt().getEpochSecond())
                .build();
        Session session = call("checkout session creation", () -> stripe.v1().checkout().sessions().create(params, idempotent(idempotencyKey)));
        if (Boolean.TRUE.equals(session.getLivemode())) throw new ProviderRejectedException("A live-mode session is refused while the R5 gates are open");
        if (session.getId() == null || session.getUrl() == null) throw new ProviderRejectedException("Stripe returned no session or URL");
        return new ProviderSession(session.getId(), session.getUrl());
    }

    @Override
    protected ExpireResult doExpireSession(String sessionRef, String idempotencyKey) {
        Session session = call("checkout session lookup", () -> stripe.v1().checkout().sessions().retrieve(sessionRef));
        ExpireResult known = expireResult(session);
        if (known != null) return known;
        try {
            Session expired = stripe.v1().checkout().sessions().expire(sessionRef, idempotent(idempotencyKey));
            ExpireResult result = expireResult(expired);
            if (result != null) return result;
            throw new ProviderRejectedException("Stripe didn't expire the session");
        } catch (InvalidRequestException e) {
            // the session completed or expired meanwhile: the provider's current state decides
            ExpireResult after = expireResult(call("checkout session lookup", () -> stripe.v1().checkout().sessions().retrieve(sessionRef)));
            if (after != null) return after;
            throw rejected("checkout session expiry", e);
        } catch (StripeException e) {
            throw map("checkout session expiry", e);
        }
    }

    private static ExpireResult expireResult(Session s) {
        if ("complete".equals(s.getStatus())) return ExpireResult.ALREADY_COMPLETE;
        if ("expired".equals(s.getStatus())) return ExpireResult.EXPIRED;
        return null;
    }

    // ---- payment verification (PO U1, D6) ----

    /**
     * {@code providerRef} is the first invoice {@code in_…}. Only a {@code subscription_create} invoice that Stripe's own checkout
     * record links to one complete Agafari checkout carries Agafari metadata; any other invoice comes back without it, so the
     * core quarantines it (no ledger row, no refund). Evidence that doesn't qualify is reported, never smoothed over.
     */
    @Override
    protected VerifiedPayment doVerifyPayment(String providerRef) {
        if (providerRef == null || !providerRef.startsWith("in_")) throw new ProviderRejectedException("A Stripe payment is verified by its invoice");
        Invoice invoice = call("invoice lookup", () -> stripe.v1().invoices().retrieve(providerRef,
                InvoiceRetrieveParams.builder().addExpand("payments").addExpand("amount_paid_off_stripe").build(), RequestOptions.getDefault()));
        if (Boolean.TRUE.equals(invoice.getLivemode())) throw new ProviderRejectedException("A live-mode invoice is refused while the R5 gates are open");
        if (!providerRef.equals(invoice.getId())) throw new ProviderRejectedException("Stripe returned another invoice");
        switch (String.valueOf(invoice.getStatus())) {
            case "paid" -> {
                // continued below
            }
            case "draft", "open" -> {
                return new VerifiedPayment(providerRef, ProviderPaymentStatus.PENDING, null, null, Map.of());
            }
            default -> {
                return new VerifiedPayment(providerRef, ProviderPaymentStatus.FAILED, null, null, Map.of());
            }
        }
        Long paidAt = invoice.getStatusTransitions() == null ? null : invoice.getStatusTransitions().getPaidAt();
        Currency currency = currency(invoice.getCurrency());
        if (paidAt == null || currency == null || invoice.getAmountPaid() == null || invoice.getAmountPaid() <= 0) {
            return new VerifiedPayment(providerRef, ProviderPaymentStatus.PENDING, null, null, Map.of()); // not yet a usable payment
        }
        Money amount = new Money(invoice.getAmountPaid(), currency);
        Instant tPay = Instant.ofEpochSecond(paidAt);
        String subscription = subscriptionOf(invoice);
        if (!"subscription_create".equals(invoice.getBillingReason()) || subscription == null) {
            return new VerifiedPayment(providerRef, ProviderPaymentStatus.SUCCEEDED, amount, tPay, Map.of()); // D6: not an initial invoice → quarantine
        }
        Optional<Session> origin = checkoutOf(subscription, providerRef);
        if (origin.isEmpty()) return new VerifiedPayment(providerRef, ProviderPaymentStatus.SUCCEEDED, amount, tPay, Map.of()); // not correlated → quarantine
        Session session = origin.get();
        if (!"paid".equals(session.getPaymentStatus())) return new VerifiedPayment(providerRef, ProviderPaymentStatus.PENDING, null, null, Map.of());
        Map<String, String> metadata = Map.of(PaymentMetadata.CHECKOUT_ID, session.getMetadata().get(META_CHECKOUT), PaymentMetadata.BUSINESS_ID,
                session.getMetadata().get(META_BUSINESS), PaymentMetadata.PROVIDER_SESSION_REF, session.getId());
        return new VerifiedPayment(providerRef, ProviderPaymentStatus.SUCCEEDED, amount, tPay, metadata, evidence(invoice, subscription, amount));
    }

    /** PO U1: the invoice must represent exactly one successful, qualifying card payment for its whole amount. */
    private ProviderBilling evidence(Invoice inv, String subscriptionRef, Money amount) {
        String ref = inv.getId();
        long paid = amount.minor();
        if (!Objects.equals(inv.getTotal(), paid) || !zero(inv.getAmountRemaining()) || !zero(inv.getStartingBalance()) || !zero(inv.getAmountPaidOffStripe())
                || !zero(inv.getAmountOverpaid()) || !zero(inv.getPrePaymentCreditNotesAmount()) || !zero(inv.getPostPaymentCreditNotesAmount())) {
            return ProviderBilling.notQualifying(ref, "the invoice isn't paid in full by one charge (credit, off-Stripe payment, balance or credit note)");
        }
        if (inv.getTotalTaxes() != null && !inv.getTotalTaxes().isEmpty()) return ProviderBilling.notQualifying(ref, "the invoice carries tax (tax is off, AD-16)");
        if (inv.getTotalDiscountAmounts() != null && inv.getTotalDiscountAmounts().stream().anyMatch(d -> d.getAmount() != null && d.getAmount() != 0)) {
            return ProviderBilling.notQualifying(ref, "the invoice carries a discount");
        }
        List<InvoiceLineItem> lines = inv.getLines() == null ? List.of() : inv.getLines().getData();
        if (lines.size() != 1 || Boolean.TRUE.equals(inv.getLines().getHasMore())) return ProviderBilling.notQualifying(ref, "the invoice doesn't have exactly one line");
        InvoiceLineItem line = lines.get(0);
        String price = line.getPricing() == null || line.getPricing().getPriceDetails() == null ? null : line.getPricing().getPriceDetails().getPrice();
        if (price == null || !Objects.equals(line.getQuantity(), 1L) || line.getPeriod() == null || line.getPeriod().getStart() == null || line.getPeriod().getEnd() == null
                || !Objects.equals(line.getAmount(), paid)) {
            return ProviderBilling.notQualifying(ref, "the invoice line isn't one unit of one price for the whole amount");
        }
        // exactly one paid payment record; any other record must be cancelled (never ambiguous)
        List<InvoicePayment> payments = inv.getPayments() == null ? List.of() : inv.getPayments().getData();
        List<InvoicePayment> paidRecords = payments.stream().filter(p -> "paid".equals(p.getStatus())).toList();
        if (paidRecords.size() != 1 || payments.stream().anyMatch(p -> !"paid".equals(p.getStatus()) && !"canceled".equals(p.getStatus()))
                || (inv.getPayments() != null && Boolean.TRUE.equals(inv.getPayments().getHasMore()))) {
            return ProviderBilling.notQualifying(ref, "the invoice doesn't have exactly one unambiguous paid payment record");
        }
        InvoicePayment record = paidRecords.get(0);
        if (record.getPayment() == null || !"payment_intent".equals(record.getPayment().getType()) || record.getPayment().getPaymentIntent() == null
                || !Objects.equals(record.getAmountPaid(), paid) || !amount.currency().name().equalsIgnoreCase(String.valueOf(record.getCurrency()))) {
            return ProviderBilling.notQualifying(ref, "the paid record isn't one PaymentIntent for the whole amount");
        }
        String paymentIntentRef = record.getPayment().getPaymentIntent();
        PaymentIntent intent = call("payment intent lookup", () -> stripe.v1().paymentIntents().retrieve(paymentIntentRef,
                PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), RequestOptions.getDefault()));
        Charge charge = intent.getLatestChargeObject();
        if (!"succeeded".equals(intent.getStatus()) || !Objects.equals(intent.getAmountReceived(), paid) || Boolean.TRUE.equals(intent.getLivemode())
                || !amount.currency().name().equalsIgnoreCase(String.valueOf(intent.getCurrency()))) {
            return ProviderBilling.notQualifying(ref, "the PaymentIntent didn't succeed for the whole amount");
        }
        if (charge == null || !"succeeded".equals(charge.getStatus()) || !Boolean.TRUE.equals(charge.getPaid()) || !Boolean.TRUE.equals(charge.getCaptured())
                || Boolean.TRUE.equals(charge.getRefunded()) || !Objects.equals(charge.getAmount(), paid) || charge.getPaymentMethodDetails() == null
                || !"card".equals(charge.getPaymentMethodDetails().getType())) {
            return ProviderBilling.notQualifying(ref, "the charge isn't one captured, unrefunded card charge for the whole amount");
        }
        Subscription sub = call("subscription lookup", () -> stripe.v1().subscriptions().retrieve(subscriptionRef));
        List<SubscriptionItem> items = sub.getItems() == null ? List.of() : sub.getItems().getData();
        if (Boolean.TRUE.equals(sub.getLivemode()) || items.size() != 1 || !"active".equals(sub.getStatus()) || !Objects.equals(sub.getCustomer(), inv.getCustomer())) {
            return ProviderBilling.notQualifying(ref, "the subscription isn't one active test-mode item of this customer");
        }
        SubscriptionItem item = items.get(0);
        PriceInterval interval = item.getPrice() == null || item.getPrice().getRecurring() == null || !Objects.equals(item.getPrice().getRecurring().getIntervalCount(), 1L)
                ? null : switch (String.valueOf(item.getPrice().getRecurring().getInterval())) {
                    case "month" -> PriceInterval.MONTH;
                    case "year" -> PriceInterval.YEAR;
                    default -> null;
                };
        if (interval == null || !price.equals(item.getPrice().getId()) || !Objects.equals(item.getQuantity(), 1L)) {
            return ProviderBilling.notQualifying(ref, "the subscription item isn't the invoiced monthly or yearly price");
        }
        // PO Q2: the invoice line's service period is authoritative, and must be the subscription's current period
        if (!Objects.equals(item.getCurrentPeriodStart(), line.getPeriod().getStart()) || !Objects.equals(item.getCurrentPeriodEnd(), line.getPeriod().getEnd())) {
            return ProviderBilling.notQualifying(ref, "the invoice period isn't the subscription's current period");
        }
        return new ProviderBilling(ref, subscriptionRef, inv.getCustomer(), price, interval, Instant.ofEpochSecond(line.getPeriod().getStart()),
                Instant.ofEpochSecond(line.getPeriod().getEnd()), paymentIntentRef, null);
    }

    /** D6: Stripe's own record of which checkout created the subscription: exactly one complete session whose invoice is this one. */
    private Optional<Session> checkoutOf(String subscriptionRef, String invoiceRef) {
        List<Session> sessions = call("checkout session lookup", () -> stripe.v1().checkout().sessions().list(SessionListParams.builder().setSubscription(subscriptionRef).setLimit(2L).build()))
                .getData();
        if (sessions.size() != 1) return Optional.empty();
        Session s = sessions.get(0);
        Map<String, String> meta = s.getMetadata() == null ? Map.of() : s.getMetadata();
        boolean ok = "complete".equals(s.getStatus()) && "subscription".equals(s.getMode()) && invoiceRef.equals(s.getInvoice()) && !Boolean.TRUE.equals(s.getLivemode())
                && meta.get(META_CHECKOUT) != null && meta.get(META_BUSINESS) != null;
        return ok ? Optional.of(s) : Optional.empty();
    }

    private static String subscriptionOf(Invoice inv) {
        return inv.getParent() == null || inv.getParent().getSubscriptionDetails() == null ? null : inv.getParent().getSubscriptionDetails().getSubscription();
    }

    // ---- D4: the subscription a payment created ----

    @Override
    protected ProviderSubscriptionState doSubscriptionOfPayment(String providerPaymentRef) {
        Invoice inv;
        try {
            inv = stripe.v1().invoices().retrieve(providerPaymentRef);
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) return new ProviderSubscriptionState(null, null, ProviderSubscriptionState.Status.NOT_FOUND, null, null, false, false);
            throw rejected("invoice lookup", e);
        } catch (StripeException e) {
            throw map("invoice lookup", e);
        }
        String subscriptionRef = subscriptionOf(inv);
        if (subscriptionRef == null) return new ProviderSubscriptionState(null, inv.getCustomer(), ProviderSubscriptionState.Status.NOT_FOUND, null, null, false, Boolean.TRUE.equals(inv.getLivemode()));
        Subscription sub = call("subscription lookup", () -> stripe.v1().subscriptions().retrieve(subscriptionRef));
        Optional<Session> origin = checkoutOf(subscriptionRef, providerPaymentRef);
        var open = call("open invoice lookup", () -> stripe.v1().invoices().list(InvoiceListParams.builder().setCustomer(sub.getCustomer())
                .setStatus(InvoiceListParams.Status.OPEN).setLimit(100L).build()));
        boolean others = Boolean.TRUE.equals(open.getHasMore()) || open.getData().stream().anyMatch(i -> !subscriptionRef.equals(subscriptionOf(i)));
        return new ProviderSubscriptionState(subscriptionRef, sub.getCustomer(), status(sub.getStatus()), origin.map(s -> s.getMetadata().get(META_CHECKOUT)).orElse(null),
                origin.map(s -> s.getMetadata().get(META_BUSINESS)).orElse(null), others, Boolean.TRUE.equals(sub.getLivemode()) || Boolean.TRUE.equals(inv.getLivemode()));
    }

    @Override
    protected SubscriptionCancelResult doCancelSubscription(String subscriptionRef) {
        try {
            Subscription cancelled = stripe.v1().subscriptions().cancel(subscriptionRef,
                    SubscriptionCancelParams.builder().setInvoiceNow(false).setProrate(false).build(), RequestOptions.getDefault()); // DELETE: no idempotency key (Stripe)
            if ("canceled".equals(cancelled.getStatus())) return SubscriptionCancelResult.CANCELLED;
            throw new ProviderRejectedException("Stripe didn't cancel the subscription");
        } catch (InvalidRequestException e) {
            Subscription now = call("subscription lookup", () -> stripe.v1().subscriptions().retrieve(subscriptionRef));
            if ("canceled".equals(now.getStatus())) return SubscriptionCancelResult.ALREADY_CANCELLED;
            throw rejected("subscription cancellation", e);
        } catch (StripeException e) {
            throw map("subscription cancellation", e);
        }
    }

    private static ProviderSubscriptionState.Status status(String s) {
        return switch (String.valueOf(s)) {
            case "active" -> ProviderSubscriptionState.Status.ACTIVE;
            case "past_due" -> ProviderSubscriptionState.Status.PAST_DUE;
            case "unpaid" -> ProviderSubscriptionState.Status.UNPAID;
            case "incomplete" -> ProviderSubscriptionState.Status.INCOMPLETE;
            case "canceled" -> ProviderSubscriptionState.Status.CANCELED;
            case "incomplete_expired" -> ProviderSubscriptionState.Status.INCOMPLETE_EXPIRED;
            default -> ProviderSubscriptionState.Status.OTHER;
        };
    }

    // ---- not in R5-02 ----

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
        return verifier;
    }

    // ---- helpers ----

    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(String what, StripeCall<T> body) {
        try {
            return body.run();
        } catch (StripeException e) {
            throw map(what, e);
        }
    }

    /** Never the exception's message (it may echo request details): its class and Stripe's error code only. */
    private static RuntimeException map(String what, StripeException e) {
        Integer status = e.getStatusCode();
        if (e instanceof ApiConnectionException || e instanceof RateLimitException || (status != null && (status == 429 || status >= 500))) {
            return new ProviderTimeoutException("Stripe " + what + " did not complete (" + e.getClass().getSimpleName() + ")");
        }
        return rejected(what, e);
    }

    private static ProviderRejectedException rejected(String what, StripeException e) {
        return new ProviderRejectedException("Stripe refused " + what + " (" + e.getClass().getSimpleName() + (e.getCode() == null ? "" : ", " + e.getCode()) + ")");
    }

    private static RequestOptions idempotent(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    private static boolean zero(Long value) {
        return value == null || value == 0L;
    }

    private static Currency currency(String code) {
        if (code == null) return null;
        try {
            return Currency.valueOf(code.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UnsupportedOperationException notAuthorized(String operation, String story) {
        return new UnsupportedOperationException("Stripe " + operation + " is not implemented: it belongs to " + story + ", which is not authorized");
    }

    @Override
    public String toString() {
        return "StripeProviderClient[provider=" + PROVIDER + ", apiVersion=" + apiVersion() + ", credentials=" + credentials + "]";
    }
}
