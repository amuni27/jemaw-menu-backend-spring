package com.agafari.com.billing;

import com.agafari.com.dto.response.OwnerPlanView;
import com.agafari.com.notifications.PaymentReviewCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AGF SUB-R4-11 (DD §8.5, R-2, I1): a payment of a restaurant is under review while any exception involving customer funds
 * is {@code AUTO_HANDLING}, {@code OPEN}, {@code IN_REVIEW} or {@code ESCALATED}. Read by the checkout block, the owner's
 * plan view and the payment-request pause only; publishing, entitlements and self-service never consult it. Billing tables
 * exist only on PostgreSQL, so on H2 nothing is ever under review.
 */
@Component
public class PaymentReview implements PaymentReviewCheck {

    private static final String OPEN = "business_id = ? and involves_customer_funds and status in ('AUTO_HANDLING', 'OPEN', 'IN_REVIEW', 'ESCALATED')";

    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    public PaymentReview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Read outside or inside a transaction (the plan view and notice delivery run outside one). */
    private boolean onPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                    c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
            postgres = known;
        }
        return known;
    }

    @Override
    public boolean underReview(String businessId) {
        return onPostgres() && jdbc.queryForObject("select exists (select 1 from billing_exception where " + OPEN + ")", Boolean.class, businessId);
    }

    /** The earliest open review, as the owner sees it. */
    public Optional<OwnerPlanView.PaymentUnderReview> current(String businessId) {
        if (!onPostgres()) return Optional.empty();
        List<Map<String, Object>> rows = jdbc.queryForList("select id, amount_minor, currency, paid_at, created_at from billing_exception where " + OPEN
                + " order by created_at, id limit 1", businessId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        return Optional.of(new OwnerPlanView.PaymentUnderReview(r.get("amount_minor") == null ? null : ((Number) r.get("amount_minor")).longValue(), (String) r.get("currency"),
                r.get("paid_at") == null ? null : ((Timestamp) r.get("paid_at")).toInstant(), (String) r.get("id"), ((Timestamp) r.get("created_at")).toInstant()));
    }
}
