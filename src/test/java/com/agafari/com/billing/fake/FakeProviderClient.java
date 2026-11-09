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
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.billing.port.VerifiedPayment;

import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *   <li>{@link Script#LOOKUP_LAG}: {@code findRefund} finds nothing for {@code laggedLookups} calls, even when the refund exists.</li>
 * </ul>
 * Capability toggles ({@link #capabilities(ProviderCapabilities)}): without idempotent create or refund a repeated key creates
 * a new object; without session expiry or refund lookup those calls are unsupported.
 */
public final class FakeProviderClient extends GuardedProviderClient {

    public enum Script { SUCCESS, DECLINE, DELAY, TIMEOUT_AFTER_ACCEPT, DUPLICATE, OUT_OF_ORDER, ALREADY_COMPLETE_ON_EXPIRE, LOOKUP_LAG }

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

    public int createCalls() {
        return createCalls.get();
    }

    public boolean createRanInTransaction() {
        return createRanInTransaction;
    }

    public synchronized void reset() {
        script = Script.SUCCESS;
        capabilities = ALL;
        onCreate = () -> { };
        createRanInTransaction = false;
        createCalls.set(0);
        nextSession = 0;
        sessions.clear();
        timedOut.clear();
    }

    public int refundsCreated() {
        return nextRefund;
    }

    public int sessionsCreated() {
        return nextSession;
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
        return script == Script.ALREADY_COMPLETE_ON_EXPIRE ? ExpireResult.ALREADY_COMPLETE : ExpireResult.EXPIRED;
    }

    @Override
    protected VerifiedPayment doVerifyPayment(String ref) {
        int fetch = fetches.merge(ref, 1, Integer::sum);
        if (script == Script.DECLINE) return new VerifiedPayment(ref, ProviderPaymentStatus.FAILED, AMOUNT, null, Map.of());
        if (script == Script.DELAY && fetch <= delayedFetches) return new VerifiedPayment(ref, ProviderPaymentStatus.PENDING, AMOUNT, null, Map.of());
        return new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, AMOUNT, PAID_AT, Map.of());
    }

    @Override
    protected RefundSubmission doSubmitRefund(String paymentRef, Money amount, String key, Map<String, String> metadata) {
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
        List<ProviderRecord> found = new ArrayList<>(records.stream().filter(r -> r.type() == type && !r.occurredAt().isBefore(since)).sorted(Comparator.comparing(ProviderRecord::occurredAt)).toList());
        if (script == Script.OUT_OF_ORDER) return found.reversed();
        if (script == Script.DUPLICATE) found.addAll(List.copyOf(found));
        return found;
    }

    @Override
    protected SignatureVerifier doSignatureVerifier() {
        return (headers, body) -> sign(body).equals(headers.get(SIGNATURE_HEADER));
    }

    public static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
