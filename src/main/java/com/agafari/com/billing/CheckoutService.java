package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.CheckoutPlan;
import com.agafari.com.billing.port.CheckoutRequest;
import com.agafari.com.billing.port.CheckoutSession;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.CurrentState;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSession;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.dto.response.CheckoutResponse;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.exception.CheckoutRefusedException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.MonetizationNotConfiguredException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.PlanNotEnabledException;
import com.agafari.com.exception.PlanRequestPendingException;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.PlanPrice;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanPriceRepository;
import com.agafari.com.jpa.repository.PlanRequestRepository;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AGF SUB-R4-05 (BD-14, DD §7.2): reserve, call the provider outside any transaction, record. The class is deliberately not
 * transactional. With {@code billing-enabled} off nothing here touches the database, so the H2 demo is unaffected.
 */
@Slf4j
@Service
public class CheckoutService {

    private static final Duration QUOTE_LIFETIME = Duration.ofHours(24); // PD-2
    private static final Duration BACKSTOP_DELAY = Duration.ofMinutes(2);
    private static final int MAX_KEY_LENGTH = 255;
    private static final String COLUMNS = "id, business_id, price_id, kind, status, provider_session_url, expires_at, created_at, expected_amount_minor, expected_currency, request_hash, provider_session_ref, row_version";

    public record Outcome(HttpStatus status, CheckoutResponse checkout) {
    }

    record Row(String id, String businessId, String priceId, CheckoutKind kind, String status, String url, Instant expiresAt, Instant createdAt, Money expected, String requestHash, String ref, int rowVersion) {
    }

    record Reservation(Row row, boolean fresh, BillingStrategy strategy) {
    }

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(rs.getString("id"), rs.getString("business_id"), rs.getString("price_id"), CheckoutKind.valueOf(rs.getString("kind")),
            rs.getString("status"), rs.getString("provider_session_url"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            new Money(rs.getLong("expected_amount_minor"), Currency.valueOf(rs.getString("expected_currency"))), rs.getString("request_hash"), rs.getString("provider_session_ref"), rs.getInt("row_version"));

    private final JdbcTemplate jdbc;
    private final BillingStrategyRegistry registry;
    private final MonetizationLocks locks;
    private final PlanPriceRepository prices;
    private final MonetizationConfigRepository configs;
    private final BusinessSubscriptionRepository subscriptions;
    private final PlanRequestRepository planRequests;
    private final JobQueue jobs;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private final CheckoutCancellation cancellation;
    private final PaymentReview review;
    private final Set<Market> enabled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CheckoutService(JdbcTemplate jdbc, BillingStrategyRegistry registry, MonetizationLocks locks, PlanPriceRepository prices, MonetizationConfigRepository configs,
                           BusinessSubscriptionRepository subscriptions, PlanRequestRepository planRequests, JobQueue jobs, MonetizationClock clock,
                           PlatformTransactionManager transactions, BillingFlags flags, CheckoutCancellation cancellation,
                           PaymentReview review) {
        this.review = review;
        this.jdbc = jdbc;
        this.registry = registry;
        this.locks = locks;
        this.prices = prices;
        this.configs = configs;
        this.subscriptions = subscriptions;
        this.planRequests = planRequests;
        this.jobs = jobs;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.cancellation = cancellation;
        if (flags.us().billingEnabled()) enabled.add(Market.US);
        if (flags.et().billingEnabled()) enabled.add(Market.ET);
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setBillingEnabled(Market market, boolean on) {
        if (on) enabled.add(market);
        else enabled.remove(market);
    }

    public Outcome create(String businessId, String ownerId, String priceId, String idempotencyKey) {
        return create(businessId, ownerId, priceId, idempotencyKey, null, null);
    }

    public Outcome create(String businessId, String ownerId, String priceId, String idempotencyKey, String replaceCheckoutId, Integer replaceRowVersion) {
        requireAnyMarketEnabled();
        if (priceId == null || priceId.isBlank()) throw new FieldValidationException(Map.of("priceId", "A price is required"));
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new FieldValidationException(Map.of("Idempotency-Key", "An Idempotency-Key of up to 255 characters is required"));
        }
        Market market = enabledMarketOf(businessId);
        BillingStrategy strategy = registry.forMarket(market);
        Reservation reservation = reserve(businessId, ownerId, priceId, idempotencyKey, market, strategy, replaceCheckoutId, replaceRowVersion);
        if (!reservation.fresh()) return existing(reservation.row());
        return callProvider(reservation);
    }

    public CheckoutResponse get(String businessId, String id) {
        requireAnyMarketEnabled();
        enabledMarketOf(businessId);
        List<Row> rows = jdbc.query("select " + COLUMNS + " from checkout_session where id = ? and business_id = ?", MAPPER, id, businessId);
        if (rows.isEmpty()) throw new NotFoundException("Checkout not found");
        return response(rows.get(0));
    }

    private void requireAnyMarketEnabled() {
        if (enabled.isEmpty()) throw new NotFoundException("Not found");
    }

    private Market enabledMarketOf(String businessId) {
        String country = jdbc.queryForObject("select country from business where id = ?", String.class, businessId);
        Market market = Market.fromCountry(country).orElseThrow(() -> new NotFoundException("Not found"));
        if (!enabled.contains(market)) throw new NotFoundException("Not found");
        return market;
    }

    /** TX-A. If the database rejects what the locks should have prevented, the outcome is still a clean one. */
    Reservation reserve(String businessId, String ownerId, String priceId, String key, Market market, BillingStrategy strategy) {
        return reserve(businessId, ownerId, priceId, key, market, strategy, null, null);
    }

    Reservation reserve(String businessId, String ownerId, String priceId, String key, Market market, BillingStrategy strategy, String replaceId, Integer replaceVersion) {
        try {
            return transaction.execute(s -> reserveLocked(businessId, ownerId, priceId, key, market, strategy, replaceId, replaceVersion));
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains("uk_checkout_session_idempotency")) {
                return transaction.execute(s -> reserveLocked(businessId, ownerId, priceId, key, market, strategy, replaceId, replaceVersion));
            }
            if (e.getMessage() != null && e.getMessage().contains("uk_checkout_session_active")) throw active();
            // AGF SUB-R4-15 (P2): the change's earlier checkout can still be paid (OPEN or CANCEL_REQUESTED)
            if (e.getMessage() != null && e.getMessage().contains("uk_checkout_session_change_payable")) {
                throw new CheckoutRefusedException(HttpStatus.CONFLICT, CheckoutRefusedException.ACTIVE, "An earlier checkout for this plan change can still be paid. Please try again later.");
            }
            throw e;
        }
    }

    private Reservation reserveLocked(String businessId, String ownerId, String priceId, String key, Market market, BillingStrategy strategy, String replaceId, Integer replaceVersion) {
        locks.configShared();
        locks.restaurant(businessId);
        // AGF SUB-R4-11 (R-2): no paid checkout of any kind, new or repeated, while a payment is under review
        if (review.underReview(businessId)) {
            throw new CheckoutRefusedException(HttpStatus.CONFLICT, CheckoutRefusedException.PAYMENT_UNDER_REVIEW,
                    "We received your payment and are reviewing it. You will not lose this payment; we will contact you.");
        }
        Instant now = clock.now();
        PlanPrice price = prices.findById(priceId).filter(p -> p.getMarket().equals(market.name()))
                .orElseThrow(() -> new FieldValidationException(Map.of("priceId", "This price isn't available for your restaurant")));
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(MonetizationNotConfiguredException::new);
        BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        CheckoutPlan plan = strategy.planCheckout(new CheckoutRequest(businessId, priceId),
                new CurrentState(current == null ? null : current.getId(), current == null ? null : current.getPlanCode(), current == null ? null : current.getSource()));
        String hash = hash(priceId, plan);

        List<Row> sameKey = jdbc.query("select " + COLUMNS + " from checkout_session where business_id = ? and idempotency_key = ?", MAPPER, businessId, key);
        if (!sameKey.isEmpty()) {
            if (!sameKey.get(0).requestHash().equals(hash)) {
                throw new CheckoutRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, CheckoutRefusedException.KEY_REUSED, "This Idempotency-Key was used for a different request.");
            }
            return new Reservation(sameKey.get(0), false, strategy);
        }

        if (!price.isActive() || price.getValidFrom().isAfter(now) || !config.models().contains(MonetizationModel.valueOf(price.getPlanCode()))) throw new PlanNotEnabledException();
        if (planRequests.findPendingByBusinessId(businessId).isPresent()) throw new PlanRequestPendingException();

        List<Row> active = jdbc.query("select " + COLUMNS + " from checkout_session where business_id = ? and status in ('CREATING', 'OPEN')", MAPPER, businessId);
        if (!active.isEmpty()) {
            Row current2 = active.get(0);
            if (current2.priceId().equals(priceId)) return new Reservation(current2, false, strategy);
            if (replaceId == null && replaceVersion == null) throw active();
            if (!current2.id().equals(replaceId) || replaceVersion == null || current2.rowVersion() != replaceVersion) {
                throw new CheckoutRefusedException(HttpStatus.CONFLICT, CheckoutRefusedException.VERSION_CONFLICT, "Your checkout changed. Please review it and try again.");
            }
            cancellation.request(current2.id(), current2.ref(), CheckoutCancellation.OWNER_REPLACED);
        }

        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, ?, ?) on conflict (business_id, provider) do nothing",
                UUID.randomUUID().toString(), businessId, market.name(), strategy.provider());
        String id = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(QUOTE_LIFETIME);
        // The amount comes from plan_price only (the story's rule); the strategy decides the kind and what it replaces.
        Money expected = new Money(price.getAmountMinor(), Currency.valueOf(price.getCurrency()));
        // AGF SUB-R4-15 (P2): a downgrade-renewal checkout names the exact open, unfunded change it pays for
        String scheduledChangeId = null;
        if (plan.kind() == CheckoutKind.DOWNGRADE_RENEWAL) {
            List<String> open = jdbc.queryForList("select id from scheduled_change where business_id = ? and from_subscription_id = ? and target_price_id = ? and kind = 'DOWNGRADE' "
                    + "and status in ('PENDING', 'AWAITING_PAYMENT') and funding_payment_id is null", String.class, businessId, plan.replacesSubscriptionId(), priceId);
            if (open.size() != 1) {
                throw new CheckoutRefusedException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKOUT_NO_SCHEDULED_CHANGE", "There is no scheduled plan change waiting for this payment.");
            }
            scheduledChangeId = open.get(0);
        }
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, replaces_subscription_id, provider, status, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at, scheduled_change_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, 'CREATING', ?, ?, ?, ?, ?, ?, ?)",
                id, businessId, ownerId, priceId, config.getId(), plan.kind().name(), plan.replacesSubscriptionId(), strategy.provider(), key, hash,
                Timestamp.from(expiresAt), expected.minor(), expected.currency().name(), Timestamp.from(now), scheduledChangeId);
        jobs.enqueue(CheckoutCreateHandler.TYPE, id, new CheckoutCreatePayload(id), BACKSTOP_DELAY);
        return new Reservation(new Row(id, businessId, priceId, plan.kind(), "CREATING", null, expiresAt, now, expected, hash, null, 0), true, strategy);
    }

    private static CheckoutRefusedException active() {
        return new CheckoutRefusedException(HttpStatus.CONFLICT, CheckoutRefusedException.ACTIVE, "You already have a checkout in progress for another plan.");
    }

    /** DD §7.1: the request hash covers the price, the kind and the subscription it expects to replace. */
    static String hash(String priceId, CheckoutPlan plan) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((priceId + "|" + plan.kind() + "|" + plan.replacesSubscriptionId()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Outcome existing(Row row) {
        return new Outcome("CREATING".equals(row.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK, response(row));
    }

    /** Runs outside any transaction: the guard in every client refuses otherwise. A guard failure is a bug and propagates. */
    private Outcome callProvider(Reservation reservation) {
        Row row = reservation.row();
        BillingStrategy strategy = reservation.strategy();
        ProviderSession session;
        try {
            session = strategy.client().createSession(sessionRequest(strategy, row), providerKey(row.id()));
        } catch (ProviderRejectedException e) {
            markCreateFailed(row, "PROVIDER_REFUSED");
            throw new CheckoutRefusedException(HttpStatus.BAD_GATEWAY, CheckoutRefusedException.CREATE_FAILED, "The payment provider refused this checkout. Please try again.");
        } catch (ProviderTimeoutException e) {
            return new Outcome(HttpStatus.ACCEPTED, response(row)); // the backstop job recovers it
        }
        Row opened = recordSession(row, session, strategy.provider());
        if ("CANCEL_REQUESTED".equals(opened.status())) throw cancelled();
        return new Outcome(HttpStatus.CREATED, response(opened));
    }

    private static CheckoutRefusedException cancelled() {
        return new CheckoutRefusedException(HttpStatus.CONFLICT, CheckoutRefusedException.CANCELLED, "This checkout was cancelled.");
    }

    /** AGF SUB-R5-02 (Q1): with the quoted price's interval and provider reference, and the expected current row. */
    private ProviderSessionRequest sessionRequest(BillingStrategy strategy, Row row) {
        Map<String, Object> quote = jdbc.queryForMap("select p.billing_interval, p.provider_price_ref, c.replaces_subscription_id from checkout_session c "
                + "join plan_price p on p.id = c.price_id where c.id = ?", row.id());
        return strategy.sessionRequest(new CheckoutSession(row.id(), row.businessId(), row.kind(), row.expected(), row.expiresAt(), row.priceId(),
                PriceInterval.valueOf((String) quote.get("billing_interval")), (String) quote.get("provider_price_ref"), (String) quote.get("replaces_subscription_id")));
    }

    private static String providerKey(String checkoutId) {
        return "checkout:" + checkoutId;
    }

    /** TX-B: only a row still CREATING opens; a row cancelled meanwhile keeps the reference for expiry and shows no URL. */
    private Row recordSession(Row row, ProviderSession session, String provider) {
        return transaction.execute(s -> {
            locks.restaurant(row.businessId());
            int opened = jdbc.update("update checkout_session set status = 'OPEN', provider_session_ref = ?, provider_session_url = ?, row_version = row_version + 1 where id = ? and status = 'CREATING'",
                    session.providerSessionRef(), session.url(), row.id());
            if (opened == 0 && jdbc.update("update checkout_session set provider_session_ref = ? where id = ? and status = 'CANCEL_REQUESTED' and provider_session_ref is null", session.providerSessionRef(), row.id()) == 1) {
                cancellation.enqueueExpiry(row.id());
            }
            return load(row.id());
        });
    }

    private Row load(String id) {
        List<Row> rows = jdbc.query("select " + COLUMNS + " from checkout_session where id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** C9 and DD §7.2: CREATE_FAILED counts as cancelled for the matrix, so it records when. */
    private void markCreateFailed(Row row, String reason) {
        transaction.executeWithoutResult(s -> {
            locks.restaurant(row.businessId());
            jdbc.update("update checkout_session set status = 'CREATE_FAILED', cancel_requested_at = ?, cancelled_reason = ?, row_version = row_version + 1 where id = ? and status = 'CREATING'",
                    Timestamp.from(clock.now()), reason, row.id());
        });
    }

    /** AGF SUB-R4-06: one transaction under the locks; the provider is asked later, by the job. */
    public CheckoutResponse cancel(String businessId, String id) {
        requireAnyMarketEnabled();
        enabledMarketOf(businessId);
        return response(transaction.execute(s -> {
            locks.configShared();
            locks.restaurant(businessId);
            List<Row> rows = jdbc.query("select " + COLUMNS + " from checkout_session where id = ? and business_id = ?", MAPPER, id, businessId);
            if (rows.isEmpty()) throw new NotFoundException("Checkout not found");
            Row row = rows.get(0);
            if ("CANCEL_REQUESTED".equals(row.status())) return row;
            if (!"CREATING".equals(row.status()) && !"OPEN".equals(row.status())) {
                throw new CheckoutRefusedException(HttpStatus.CONFLICT, CheckoutRefusedException.NOT_CANCELLABLE, "This checkout has already finished.");
            }
            cancellation.request(row.id(), row.ref(), CheckoutCancellation.OWNER_CANCELLED);
            return load(row.id());
        }));
    }

    /**
     * The {@code checkout.expire} job. Ends on success, on a final answer, or at {@code expires_at}. It never changes the
     * checkout: {@code CANCELLED} comes from reconciliation (SUB-R4-14), and a payment that beat the cancel goes to the matrix.
     */
    void expire(String checkoutId) {
        ProviderCallGuard.assertNoTransaction();
        Row row = load(checkoutId);
        if (row == null || !"CANCEL_REQUESTED".equals(row.status()) || row.ref() == null) return;
        if (!clock.now().isBefore(row.expiresAt())) return;
        BillingStrategy strategy = registry.forBusiness(row.businessId());
        if (!strategy.client().capabilities().sessionExpire()) return; // TA §6.2: relies on expires_at and Agafari-side refusal
        try {
            strategy.client().expireSession(row.ref(), "checkout-expire:" + row.id());
        } catch (ProviderRejectedException e) {
            log.warn("event=billing.checkout_expire_refused checkoutId={}", row.id());
        } catch (ProviderTimeoutException e) {
            throw new TransientJobException("The provider timed out expiring a checkout", e);
        }
    }

    /** The {@code checkout.create} backstop. Capabilities are read here, outside any transaction (PO decision, SUB-R4-04 D1). */
    void recover(String checkoutId) {
        ProviderCallGuard.assertNoTransaction();
        Row row = load(checkoutId);
        if (row == null || !"CREATING".equals(row.status())) return;
        // AGF SUB-R4-14 (G3, R-2): no checkout is opened at the provider while a payment of this restaurant is under review
        if (review.underReview(row.businessId())) {
            markCreateFailed(row, CheckoutRefusedException.PAYMENT_UNDER_REVIEW);
            return;
        }
        BillingStrategy strategy = registry.forBusiness(row.businessId());
        if (!enabled.contains(strategy.market())) {
            markCreateFailed(row, "BILLING_DISABLED");
            return;
        }
        ProviderCapabilities capabilities = strategy.client().capabilities();
        boolean insideWindow = !clock.now().isAfter(row.createdAt().plus(capabilities.idempotencyWindow()));
        if (!capabilities.idempotentCreate() || !insideWindow) {
            markCreateFailed(row, "NOT_RECOVERABLE");
            return;
        }
        ProviderSession session;
        try {
            session = strategy.client().createSession(sessionRequest(strategy, row), providerKey(row.id()));
        } catch (ProviderRejectedException e) {
            markCreateFailed(row, "PROVIDER_REFUSED");
            return;
        } catch (ProviderTimeoutException e) {
            throw new TransientJobException("The provider timed out creating a checkout", e);
        }
        recordSession(row, session, strategy.provider());
    }

    private static CheckoutResponse response(Row row) {
        return new CheckoutResponse(row.id(), row.status(), "OPEN".equals(row.status()) ? row.url() : null, row.expiresAt(), row.rowVersion());
    }
}
