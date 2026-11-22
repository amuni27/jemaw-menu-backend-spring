package com.agafari.com.billing.fake;

import com.agafari.com.billing.Currency;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.port.ExpireResult;
import com.agafari.com.billing.port.GuardedProviderClient;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.ProviderRefund;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSession;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.billing.port.RefundSubmission;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.RotatingSignatureVerifier;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.VerifiedPayment;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AGF SUB-R4-04: a deterministic, scriptable provider; test classpath only. Nothing depends on time or randomness.
 *
 * <p>Script catalogue:
 * <ul>
 *   <li>{@link Script#SUCCESS}: every call works; verification returns SUCCEEDED.</li>
 *   <li>{@link Script#DECLINE}: creating a session or a refund is refused; verification returns FAILED.</li>
 *   <li>{@link Script#DELAY}: verification returns PENDING for {@code delayedFetches} calls, then SUCCEEDED.</li>
 *   <li>{@link Script#TIMEOUT_AFTER_ACCEPT}: the first create or refund for a key takes effect, then throws a timeout.</li>
 *   <li>{@link Script#DUPLICATE}: a listing returns every record twice.</li>
 *   <li>{@link Script#OUT_OF_ORDER}: a listing returns records newest first.</li>
 *   <li>{@link Script#ALREADY_COMPLETE_ON_EXPIRE}: expiring a session reports it was already paid.</li>
 *   <li>{@link Script#EXPIRE_TIMEOUT}, {@link Script#EXPIRE_REFUSED}: expiring a session times out, or is definitely refused.</li>
 *   <li>{@link Script#LOOKUP_LAG}: {@code findRefund} finds nothing for {@code laggedLookups} calls, even when the refund exists.</li>
 *   <li>{@link Script#VERIFY_TIMEOUT}: verification times out (AGF SUB-R4-10).</li>
 * </ul>
 * AGF SUB-R4-10: {@link #payment(VerifiedPayment)} scripts what a re-fetch of one reference returns; DECLINE and DELAY still
 * apply on top of it. {@link #onVerify(Runnable)} runs at the start of each re-fetch, to hold or observe it.
 * Capability toggles ({@link #capabilities(ProviderCapabilities)}): without idempotent create or refund a repeated key creates
 * a new object; without session expiry or refund lookup those calls are unsupported.
 */
public final class FakeProviderClient extends GuardedProviderClient {

    public enum Script { SUCCESS, DECLINE, DELAY, TIMEOUT_AFTER_ACCEPT, DUPLICATE, OUT_OF_ORDER, ALREADY_COMPLETE_ON_EXPIRE, LOOKUP_LAG, EXPIRE_TIMEOUT, EXPIRE_REFUSED, VERIFY_TIMEOUT }

    public static final ProviderCapabilities ALL = new ProviderCapabilities(true, true, true, Duration.ofHours(24), true);
    public static final Instant PAID_AT = Instant.parse("2030-01-01T00:00:00Z");
    public static final Money AMOUNT = new Money(1000, Currency.ETB);
    private static final String SECRET = "fake-secret";
    public static final String SIGNATURE_HEADER = "X-Fake-Signature";

    private Script script = Script.SUCCESS;
    private ProviderCapabilities capabilities = ALL;
    private int delayedFetches = 1;
    private int laggedLookups = 1;
    private volatile Runnable onCreate = () -> { };
    private volatile boolean createRanInTransaction;
    private volatile String currentSecret = SECRET;
    private volatile String previousSecret;
    private volatile boolean expireRanInTransaction;
    private volatile String lastExpireKey;
    private final java.util.concurrent.atomic.AtomicInteger expireCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger createCalls = new java.util.concurrent.atomic.AtomicInteger();
    private int nextSession;
    private int nextRefund;
    private final Map<String, ProviderSession> sessions = new HashMap<>();
    private final Map<String, RefundSubmission> refundsByKey = new HashMap<>();
    private final Map<String, ProviderRefund> refundsByRequest = new HashMap<>();
    private final Map<String, Integer> fetches = new HashMap<>();
    private final Map<String, Integer> lookups = new HashMap<>();
    private final Set<String> timedOut = new HashSet<>();
    private final List<ProviderRecord> records = new ArrayList<>();
    private final Map<String, VerifiedPayment> payments = new java.util.concurrent.ConcurrentHashMap<>();
    // AGF SUB-R5-03: scripted provider invoice and subscription state re-fetches (SYNTHETIC)
    private final Map<String, com.agafari.com.billing.port.ProviderInvoiceState> invoiceStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, com.agafari.com.billing.port.ProviderSubscriptionSnapshot> subscriptionStates = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Runnable onVerify = () -> { };
    private volatile boolean verifyRanInTransaction;
    private final java.util.concurrent.atomic.AtomicInteger refundSubmitCalls = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean refundRanInTransaction;
    private volatile Runnable onRefund = () -> { };
    private final Map<String, String> lastRefundKey = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger listCalls = new java.util.concurrent.atomic.AtomicInteger();

    public FakeProviderClient script(Script value) {
        script = value;
        return this;
    }

    public FakeProviderClient capabilities(ProviderCapabilities value) {
        capabilities = value;
        return this;
    }

    public FakeProviderClient delayedFetches(int value) {
        delayedFetches = value;
        return this;
    }

    public FakeProviderClient laggedLookups(int value) {
        laggedLookups = value;
        return this;
    }

    public FakeProviderClient record(ProviderRecord value) {
        records.add(value);
        return this;
    }

    /** AGF SUB-R4-05: runs at the start of the next creates, outside the fake's own state, to hold or observe a call. */
    public FakeProviderClient onCreate(Runnable value) {
        onCreate = value;
        return this;
    }

    /** AGF SUB-R4-10: what a re-fetch of this payment's reference returns from now on. */
    public FakeProviderClient payment(VerifiedPayment value) {
        payments.put(value.providerPaymentRef(), value);
        return this;
    }

    /** AGF SUB-R4-10: a re-fetch of {@code ref} answers with {@code value}, even if it names another payment. */
    public FakeProviderClient payment(String ref, VerifiedPayment value) {
        payments.put(ref, value);
        return this;
    }

    /** AGF SUB-R5-03: the provider's current state of an invoice, as a re-fetch would report it. */
    public FakeProviderClient invoiceState(com.agafari.com.billing.port.ProviderInvoiceState value) {
        invoiceStates.put(value.invoiceRef(), value);
        return this;
    }

    /** AGF SUB-R5-03: the provider's current state of a subscription, as a re-fetch would report it. */
    public FakeProviderClient subscriptionState(com.agafari.com.billing.port.ProviderSubscriptionSnapshot value) {
        subscriptionStates.put(value.subscriptionRef(), value);
        return this;
    }

    @Override
    protected com.agafari.com.billing.port.ProviderInvoiceState doInvoiceState(String ref) {
        var s = invoiceStates.get(ref); // unscripted: an invoice that is no renewal, so it decides nothing
        return s != null ? s : new com.agafari.com.billing.port.ProviderInvoiceState(ref, com.agafari.com.billing.port.ProviderInvoiceState.Status.OTHER, null, null, null, null, null, false, false);
    }

    @Override
    protected com.agafari.com.billing.port.ProviderSubscriptionSnapshot doSubscriptionState(String ref) {
        var s = subscriptionStates.get(ref); // unscripted: the provider has no such subscription
        return s != null ? s : new com.agafari.com.billing.port.ProviderSubscriptionSnapshot(ref, null, com.agafari.com.billing.port.ProviderSubscriptionState.Status.NOT_FOUND, null, null, false);
    }

    public FakeProviderClient onVerify(Runnable value) {
        onVerify = value;
        return this;
    }

    /** AGF SUB-R4-13: every call to submit a refund, including same-key retries. */
    public int refundSubmitCalls() {
        return refundSubmitCalls.get();
    }

    /** AGF SUB-R4-13: the idempotency key of the last submission for this refund request. */
    public String lastRefundKeyFor(String refundRequestId) {
        return lastRefundKey.get(refundRequestId);
    }

    /** AGF SUB-R4-14: how often a listing was asked for. */
    public int listCalls() {
        return listCalls.get();
    }

    public boolean refundRanInTransaction() {
        return refundRanInTransaction;
    }

    /** AGF SUB-R4-13: runs at the start of each refund submission, to hold or observe it. */
    public FakeProviderClient onRefund(Runnable value) {
        onRefund = value;
        return this;
    }

    public boolean verifyRanInTransaction() {
        return verifyRanInTransaction;
    }

    public int expireCalls() {
        return expireCalls.get();
    }

    public String lastExpireKey() {
        return lastExpireKey;
    }

    public boolean expireRanInTransaction() {
        return expireRanInTransaction;
    }

    public int verifyCalls() {
        return fetches.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int createCalls() {
        return createCalls.get();
    }

    public boolean createRanInTransaction() {
        return createRanInTransaction;
    }

    public synchronized void reset() {
        subscriptionsOfPayments.clear();
        cancelled.clear();
        invoiceStates.clear();
        subscriptionStates.clear();
        script = Script.SUCCESS;
        capabilities = ALL;
        onCreate = () -> { };
        createRanInTransaction = false;
        currentSecret = SECRET;
        previousSecret = null;
        fetches.clear();
        payments.clear();
        onVerify = () -> { };
        verifyRanInTransaction = false;
        createCalls.set(0);
        expireCalls.set(0);
        lastExpireKey = null;
        expireRanInTransaction = false;
        nextSession = 0;
        sessions.clear();
        timedOut.clear();
        // AGF SUB-R4-13: refunds start afresh in every test
        nextRefund = 0;
        refundsByKey.clear();
        refundsByRequest.clear();
        lookups.clear();
        laggedLookups = 1;
        delayedFetches = 1;
        refundSubmitCalls.set(0);
        lastRefundKey.clear();
        records.clear(); // AGF SUB-R4-14: listings start empty in every test
        listCalls.set(0);
        refundRanInTransaction = false;
        onRefund = () -> { };
    }

    public int refundsCreated() {
        return nextRefund;
    }

    public int sessionsCreated() {
        return nextSession;
    }

    // AGF SUB-R5-03-C: a scripted subscription behind a payment (SYNTHETIC), and the cancellations asked for
    private final Map<String, com.agafari.com.billing.port.ProviderSubscriptionState> subscriptionsOfPayments = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> cancelled = java.util.Collections.synchronizedList(new ArrayList<>());

    public FakeProviderClient subscriptionOfPayment(String providerPaymentRef, com.agafari.com.billing.port.ProviderSubscriptionState state) {
        subscriptionsOfPayments.put(providerPaymentRef, state);
        return this;
    }

    public List<String> cancelled() {
        synchronized (cancelled) {
            return List.copyOf(cancelled);
        }
    }

    /** AGF SUB-R5-02: unscripted, the fake has no provider subscriptions (one-off payments); it reports none. */
    @Override
    protected com.agafari.com.billing.port.ProviderSubscriptionState doSubscriptionOfPayment(String providerPaymentRef) {
        var scripted = subscriptionsOfPayments.get(providerPaymentRef);
        return scripted != null ? scripted
                : new com.agafari.com.billing.port.ProviderSubscriptionState(null, null, com.agafari.com.billing.port.ProviderSubscriptionState.Status.NOT_FOUND, null, null, false, false);
    }

    /** Unscripted: nothing to cancel. Scripted: cancels once (the re-fetch then reports it CANCELED), as the provider would. */
    @Override
    protected com.agafari.com.billing.port.SubscriptionCancelResult doCancelSubscription(String subscriptionRef) {
        cancelled.add(subscriptionRef);
        for (var e : subscriptionsOfPayments.entrySet()) {
            var st = e.getValue();
            if (subscriptionRef.equals(st.subscriptionRef())) {
                if (st.ended()) return com.agafari.com.billing.port.SubscriptionCancelResult.ALREADY_CANCELLED;
                e.setValue(new com.agafari.com.billing.port.ProviderSubscriptionState(st.subscriptionRef(), st.customerRef(),
                        com.agafari.com.billing.port.ProviderSubscriptionState.Status.CANCELED, st.agafariCheckoutId(), st.agafariBusinessId(), st.otherOpenInvoices(), st.livemode()));
                return com.agafari.com.billing.port.SubscriptionCancelResult.CANCELLED;
            }
        }
        return com.agafari.com.billing.port.SubscriptionCancelResult.ALREADY_CANCELLED;
    }

    @Override
    protected ProviderCapabilities doCapabilities() {
        return capabilities;
    }

    @Override
    protected ProviderSession doCreateSession(ProviderSessionRequest request, String key) {
        createCalls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) createRanInTransaction = true;
        onCreate.run();
        synchronized (this) {
            return createLocked(key);
        }
    }

    private ProviderSession createLocked(String key) {
        if (script == Script.DECLINE) throw new ProviderRejectedException("declined");
        ProviderSession session = capabilities.idempotentCreate() ? sessions.get(key) : null;
        if (session == null) {
            session = new ProviderSession("fake_session_" + ++nextSession, "https://fake.test/pay/" + nextSession);
            sessions.put(key, session);
        }
        timeoutOnce("session:" + key);
        return session;
    }

    @Override
    protected ExpireResult doExpireSession(String ref, String key) {
        if (!capabilities.sessionExpire()) throw new UnsupportedOperationException("session expiry isn't confirmed");
        expireCalls.incrementAndGet();
        lastExpireKey = key;
        if (TransactionSynchronizationManager.isActualTransactionActive()) expireRanInTransaction = true;
        if (script == Script.EXPIRE_TIMEOUT) throw new ProviderTimeoutException("timed out");
        if (script == Script.EXPIRE_REFUSED) throw new ProviderRejectedException("refused");
        return script == Script.ALREADY_COMPLETE_ON_EXPIRE ? ExpireResult.ALREADY_COMPLETE : ExpireResult.EXPIRED;
    }

    @Override
    protected VerifiedPayment doVerifyPayment(String ref) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) verifyRanInTransaction = true;
        onVerify.run();
        int fetch;
        synchronized (this) {
            fetch = fetches.merge(ref, 1, Integer::sum);
        }
        if (script == Script.VERIFY_TIMEOUT) throw new ProviderTimeoutException("timed out");
        VerifiedPayment scripted = payments.get(ref);
        Map<String, String> metadata = scripted == null ? Map.of() : scripted.metadata();
        Money amount = scripted == null ? AMOUNT : scripted.amount();
        if (script == Script.DECLINE) return new VerifiedPayment(ref, ProviderPaymentStatus.FAILED, amount, null, metadata);
        if (script == Script.DELAY && fetch <= delayedFetches) return new VerifiedPayment(ref, ProviderPaymentStatus.PENDING, amount, null, metadata);
        return scripted != null ? scripted : new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, AMOUNT, PAID_AT, Map.of());
    }

    @Override
    protected RefundSubmission doSubmitRefund(String paymentRef, Money amount, String key, Map<String, String> metadata) {
        refundSubmitCalls.incrementAndGet();
        if (metadata.get("refund_request_id") != null) lastRefundKey.put(metadata.get("refund_request_id"), key);
        if (TransactionSynchronizationManager.isActualTransactionActive()) refundRanInTransaction = true;
        onRefund.run();
        return submitLocked(paymentRef, amount, key, metadata);
    }

    private synchronized RefundSubmission submitLocked(String paymentRef, Money amount, String key, Map<String, String> metadata) {
        if (script == Script.DECLINE) throw new ProviderRejectedException("declined");
        RefundSubmission submission = capabilities.idempotentRefund() ? refundsByKey.get(key) : null;
        if (submission == null) {
            submission = new RefundSubmission("fake_refund_" + ++nextRefund);
            refundsByKey.put(key, submission);
            refundsByRequest.put(paymentRef + "|" + metadata.get("refund_request_id"), new ProviderRefund(submission.providerRefundRef()));
        }
        timeoutOnce("refund:" + key);
        return submission;
    }

    private void timeoutOnce(String identity) {
        if (script == Script.TIMEOUT_AFTER_ACCEPT && timedOut.add(identity)) throw new ProviderTimeoutException("timed out after the provider accepted");
    }

    @Override
    protected Optional<ProviderRefund> doFindRefund(String paymentRef, String refundRequestId) {
        if (!capabilities.refundLookup()) throw new UnsupportedOperationException("refund lookup isn't confirmed");
        String identity = paymentRef + "|" + refundRequestId;
        int lookup = lookups.merge(identity, 1, Integer::sum);
        if (script == Script.LOOKUP_LAG && lookup <= laggedLookups) return Optional.empty();
        return Optional.ofNullable(refundsByRequest.get(identity));
    }

    @Override
    protected List<ProviderRecord> doListSince(Instant since, RecordType type) {
        listCalls.incrementAndGet();
        List<ProviderRecord> found = new ArrayList<>(records.stream().filter(r -> r.type() == type && !r.occurredAt().isBefore(since)).sorted(Comparator.comparing(ProviderRecord::occurredAt)).toList());
        if (script == Script.OUT_OF_ORDER) return found.reversed();
        if (script == Script.DUPLICATE) found.addAll(List.copyOf(found));
        return found;
    }

    @Override
    protected SignatureVerifier doSignatureVerifier() {
        return new RotatingSignatureVerifier(new HmacVerifier(currentSecret), previousSecret == null ? null : new HmacVerifier(previousSecret));
    }

    public FakeProviderClient secrets(String current, String previous) {
        currentSecret = current;
        previousSecret = previous;
        return this;
    }

    private record HmacVerifier(String secret) implements SignatureVerifier {
        @Override
        public boolean verify(Map<String, String> headers, byte[] body) {
            return sign(body, secret).equals(headers.get(SIGNATURE_HEADER));
        }

        @Override
        public InboundEvent identify(byte[] body) {
            try {
                JsonNode node = new ObjectMapper().readTree(body);
                return new InboundEvent(node.path("id").asString(""), node.path("type").asString(""));
            } catch (JacksonException e) {
                throw new IllegalArgumentException("Not JSON", e);
            }
        }

        /** AGF SUB-R5-03: a fake state event names the object to re-fetch in {@code state_kind} and {@code state_ref}. */
        @Override
        public Optional<com.agafari.com.billing.port.StateTrigger> stateTrigger(byte[] body) {
            try {
                JsonNode node = new ObjectMapper().readTree(body);
                String kind = node.path("state_kind").asString(""), ref = node.path("state_ref").asString("");
                if (kind.isBlank() || ref.isBlank()) return Optional.empty();
                return Optional.of(new com.agafari.com.billing.port.StateTrigger(com.agafari.com.billing.port.StateTrigger.Kind.valueOf(kind), ref));
            } catch (JacksonException e) {
                return Optional.empty();
            }
        }

        /** AGF SUB-R4-10: a fake event names its payment in {@code payment_ref}. */
        @Override
        public Optional<String> paymentReference(byte[] body) {
            try {
                String ref = new ObjectMapper().readTree(body).path("payment_ref").asString("");
                return ref.isBlank() ? Optional.empty() : Optional.of(ref);
            } catch (JacksonException e) {
                return Optional.empty();
            }
        }
    }

    public static String sign(byte[] body) {
        return sign(body, SECRET);
    }

    public static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
