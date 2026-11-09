package com.agafari.com.billing;

import com.agafari.com.jobs.JobQueue;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R4-06 (TA §6.2): the durable cancellation request. It joins the caller's transaction, so a plan disable and its
 * cancellations commit together. A session with no provider reference yet gets its expiry when TX-B records the reference.
 */
@Service
public class CheckoutCancellation {

    public static final String OWNER_CANCELLED = "OWNER_CANCELLED";
    public static final String OWNER_REPLACED = "OWNER_REPLACED";
    public static final String PLAN_DISABLED = "PLAN_DISABLED";
    public static final String CONFIG_CHANGED = "CONFIG_CHANGED";

    private final JdbcTemplate jdbc;
    private final JobQueue jobs;
    private final MonetizationClock clock;
    private final MonetizationLocks locks;

    public CheckoutCancellation(JdbcTemplate jdbc, JobQueue jobs, MonetizationClock clock, MonetizationLocks locks) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.clock = clock;
        this.locks = locks;
    }

    void request(String checkoutId, String providerSessionRef, String reason) {
        int changed = jdbc.update("update checkout_session set status = 'CANCEL_REQUESTED', cancel_requested_at = ?, cancelled_reason = ?, row_version = row_version + 1 where id = ? and status in ('CREATING', 'OPEN')",
                Timestamp.from(clock.now()), reason, checkoutId);
        if (changed == 1 && providerSessionRef != null) enqueueExpiry(checkoutId);
    }

    void enqueueExpiry(String checkoutId) {
        jobs.enqueue(CheckoutExpireHandler.TYPE, checkoutId, new CheckoutExpirePayload(checkoutId));
    }

    /**
     * Called inside the configuration change's transaction, which holds the exclusive configuration lock. PD-2: a checkout
     * stays valid while its plan remains enabled, so only checkouts of plans no longer enabled are affected. The H2 demo
     * has no billing tables and billing is refused there, so this must never run on it.
     */
    public void requestForConfiguration(Collection<String> enabledPlanCodes, boolean settingChanged) {
        if (!locks.onPostgres()) return;
        String placeholders = String.join(", ", Collections.nCopies(enabledPlanCodes.size(), "?"));
        List<Map<String, Object>> affected = jdbc.queryForList("select c.id, c.provider_session_ref from checkout_session c join plan_price p on p.id = c.price_id "
                + "where c.status in ('CREATING', 'OPEN') and p.plan_code not in (" + placeholders + ") for update of c", enabledPlanCodes.toArray());
        String reason = settingChanged ? CONFIG_CHANGED : PLAN_DISABLED;
        affected.forEach(row -> request((String) row.get("id"), (String) row.get("provider_session_ref"), reason));
    }
}
