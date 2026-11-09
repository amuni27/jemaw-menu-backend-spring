package com.agafari.com.subscription;

import com.agafari.com.billing.VerifiedPaymentRef;
import com.agafari.com.subscription.EvidenceInvalidException.Code;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AGF SUB-R4-09 (BD-16, DD §2.4): re-loads and checks evidence inside the caller's transaction, after the locks. Evidence is
 * read {@code FOR SHARE}, so it can't change before this transaction commits. The database re-checks the same rules (V23.2).
 */
@Component
class EvidenceValidator {

    private static final Set<String> VERIFICATIONS = Set.of("SIGNED_WEBHOOK+API_FETCH", "RECONCILIATION_API_FETCH");

    /**
     * @param subscriptionId the row being renewed; null for a new row
     * @param provider       the subscription's provider
     */
    record Claim(VerifiedPaymentRef ref, String businessId, String planCode, String provider, Instant periodStart, Instant periodEnd, String subscriptionId) {
    }

    /** @param alreadyAppliedTo the subscription this exact evidence already extended or created; null if fresh */
    record Result(String alreadyAppliedTo) {
    }

    private final JdbcTemplate jdbc;

    EvidenceValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Result validate(Claim claim) {
        Map<String, Object> pay = one("select * from payment where id = ? for share", claim.ref().paymentId());
        Map<String, Object> inv = one("select * from billing_invoice where id = ? for share", claim.ref().invoiceId());
        if (pay == null || inv == null) throw new EvidenceInvalidException(Code.NOT_FOUND);

        if (!"CHARGE".equals(pay.get("kind")) || !"SUCCEEDED".equals(pay.get("status")) || pay.get("verified_at") == null || !VERIFICATIONS.contains(String.valueOf(pay.get("verification")))
                || !"PAID".equals(inv.get("status")) || !claim.ref().invoiceId().equals(pay.get("invoice_id"))) {
            throw new EvidenceInvalidException(Code.NOT_VERIFIED);
        }

        requireOwnership(claim, pay, inv);

        Map<String, Object> price = one("select plan_code, market, amount_minor, currency from plan_price where id = ?", (String) inv.get("price_id"));
        if (!((Long) pay.get("amount_minor")).equals(inv.get("amount_minor")) || !pay.get("currency").equals(inv.get("currency"))
                || !inv.get("amount_minor").equals(price.get("amount_minor")) || !inv.get("currency").equals(price.get("currency"))) {
            throw new EvidenceInvalidException(Code.AMOUNT_MISMATCH); // no tax lines (AD-16) or proration (AD-8) exist yet
        }

        List<String> markets = jdbc.queryForList("select market from billing_account where business_id = ? and provider = ?", String.class, claim.businessId(), pay.get("provider"));
        if (!claim.planCode().equals(price.get("plan_code")) || markets.isEmpty() || !markets.get(0).equals(price.get("market"))) throw new EvidenceInvalidException(Code.PLAN_MISMATCH);

        if (!instant(inv, "period_start").equals(micros(claim.periodStart())) || !instant(inv, "period_end").equals(micros(claim.periodEnd()))) throw new EvidenceInvalidException(Code.PERIOD_MISMATCH);

        return notConsumed(claim);
    }

    private void requireOwnership(Claim claim, Map<String, Object> pay, Map<String, Object> inv) {
        if (!claim.businessId().equals(pay.get("business_id")) || !claim.businessId().equals(inv.get("business_id")) || !Objects.equals(claim.provider(), pay.get("provider"))) {
            throw new EvidenceInvalidException(Code.OWNER_MISMATCH);
        }
        Object onPayment = pay.get("checkout_session_id"), onInvoice = inv.get("checkout_session_id");
        if (onPayment != null && onInvoice != null && !onPayment.equals(onInvoice)) throw new EvidenceInvalidException(Code.OWNER_MISMATCH);
        Object checkoutId = onInvoice != null ? onInvoice : onPayment;
        if (checkoutId == null) return; // a recurring renewal has no interactive checkout (PO alignment, DD §2.4)
        Map<String, Object> checkout = one("select business_id, provider from checkout_session where id = ?", (String) checkoutId);
        if (!claim.businessId().equals(checkout.get("business_id")) || !pay.get("provider").equals(checkout.get("provider"))) throw new EvidenceInvalidException(Code.OWNER_MISMATCH);
    }

    private Result notConsumed(Claim claim) {
        List<Map<String, Object>> used = jdbc.queryForList("select subscription_id, payment_id, invoice_id, period_start, period_end from subscription_period where payment_id = ? or invoice_id = ?",
                claim.ref().paymentId(), claim.ref().invoiceId());
        if (used.isEmpty()) return new Result(null);
        Map<String, Object> period = used.get(0);
        boolean samePeriod = used.size() == 1 && claim.ref().paymentId().equals(period.get("payment_id")) && claim.ref().invoiceId().equals(period.get("invoice_id"))
                && instant(period, "period_start").equals(micros(claim.periodStart())) && instant(period, "period_end").equals(micros(claim.periodEnd()));
        String appliedTo = (String) period.get("subscription_id");
        boolean sameTarget = claim.subscriptionId() != null ? claim.subscriptionId().equals(appliedTo)
                : jdbc.queryForObject("select count(*) from business_subscription where id = ? and business_id = ? and status <> 'EXPIRED'", Integer.class, appliedTo, claim.businessId()) == 1;
        if (samePeriod && sameTarget) return new Result(appliedTo);
        throw new EvidenceInvalidException(Code.ALREADY_CONSUMED);
    }

    private Map<String, Object> one(String sql, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    private static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
