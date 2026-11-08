package com.agafari.com.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * AGF E4-M4 (proposal §16): transaction-scoped locks that serialize monetization writes. Always taken in this order:
 * configuration lock, then restaurant lock (no deadlock cycle).
 * <ul>
 *   <li>{@link #configExclusive()}: a monetization configuration change (E4-M2). {@link #configShared()}: every write that
 *   depends on the current configuration (owner request, fulfil, manual record). A writer and a change never overlap, so a
 *   subscription or request is written only against the configuration current at commit (invariant I2). An advisory lock
 *   because a change inserts a new version (there is no row to lock) and it also covers "not configured".</li>
 *   <li>{@link #restaurant(String)}: {@code FOR NO KEY UPDATE} on the restaurant row, taken by every transaction that creates
 *   or resolves a plan request or creates a subscription, so owner and Super Admin actions on one restaurant are serialized
 *   (invariant I1). Ordinary inserts that only reference the restaurant (menus, specials) aren't blocked.</li>
 * </ul>
 * PostgreSQL only; the in-memory H2 demo (a single local process) skips them.
 */
@Component
public class MonetizationLocks {

    public static final long CONFIG_LOCK_KEY = 4_400_000_001L;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private volatile Boolean postgres;
    private final Object heldKey = new Object(); // per-transaction record of the locks taken (AGF SUB-R2-02)
    private static final String CONFIG_HELD = "\u0000config"; // can't collide with a restaurant id

    public MonetizationLocks(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public void configExclusive() {
        if (enabled()) {
            jdbc.execute("select pg_advisory_xact_lock(" + CONFIG_LOCK_KEY + ")");
            markHeld(CONFIG_HELD);
        }
    }

    public void configShared() {
        if (enabled()) {
            jdbc.execute("select pg_advisory_xact_lock_shared(" + CONFIG_LOCK_KEY + ")");
            markHeld(CONFIG_HELD);
        }
    }

    /** AGF E4-M5: true if this transaction now runs the trial expiry job; false if another instance already does. */
    public boolean tryTrialJob(long key) {
        if (!enabled()) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(" + key + ")", Boolean.class));
    }

    public void restaurant(String businessId) {
        if (enabled()) {
            jdbc.queryForList("select id from business where id = ? for no key update", String.class, businessId);
            markHeld(businessId);
        }
    }

    /**
     * AGF E4-M8: marks this transaction as a Super Admin restaurant-ad moderation (suspend or reinstate). The V18 trigger
     * refuses those status changes without it; transaction-local, so it never reaches another request on the connection.
     */
    public void markAdminAdModeration() {
        if (enabled()) jdbc.queryForObject("select set_config('agafari.ad_actor', 'ADMIN', true)", String.class);
    }

    /**
     * AGF SUB-R2-02: refuses (IllegalStateException) unless this transaction took a configuration lock and this restaurant's
     * lock through this class. Called by the single subscription writer before every write. The H2 demo takes no locks, so
     * there is nothing to check there.
     */
    public void assertHeld(String businessId) {
        if (!enabled()) return;
        Set<String> held = heldInThisTransaction();
        if (!held.contains(CONFIG_HELD)) throw new IllegalStateException("The monetization configuration lock must be held");
        if (!held.contains(businessId)) throw new IllegalStateException("The restaurant lock must be held");
    }

    /** AGF SUB-R2-04: the narrower check for trial expiry, which has always held only the restaurant lock (no configuration dependence). */
    public void assertRestaurantHeld(String businessId) {
        if (!enabled()) return;
        if (!heldInThisTransaction().contains(businessId)) throw new IllegalStateException("The restaurant lock must be held");
    }

    @SuppressWarnings("unchecked")
    private Set<String> heldInThisTransaction() {
        Set<String> held = (Set<String>) TransactionSynchronizationManager.getResource(heldKey);
        return held == null ? Set.of() : held;
    }

    /** Remembers a lock for this transaction only; the record is dropped when it completes and hidden while it is suspended. */
    @SuppressWarnings("unchecked")
    private void markHeld(String lock) {
        Set<String> held = (Set<String>) TransactionSynchronizationManager.getResource(heldKey);
        if (held == null) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return; // not recorded: assertHeld then refuses
            Set<String> created = new HashSet<>();
            TransactionSynchronizationManager.bindResource(heldKey, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResourceIfPossible(heldKey);
                }

                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(heldKey, created);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(heldKey);
                }
            });
            held = created;
        }
        held.add(lock);
    }

    private boolean enabled() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Monetization locks must be taken inside a transaction");
        }
        if (postgres == null) {
            try (Connection c = dataSource.getConnection()) {
                postgres = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
            } catch (SQLException e) {
                throw new IllegalStateException("Can't detect the database", e);
            }
        }
        return postgres;
    }
}
