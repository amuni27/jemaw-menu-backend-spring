package com.agafari.com.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

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

    public MonetizationLocks(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public void configExclusive() {
        if (enabled()) jdbc.execute("select pg_advisory_xact_lock(" + CONFIG_LOCK_KEY + ")");
    }

    public void configShared() {
        if (enabled()) jdbc.execute("select pg_advisory_xact_lock_shared(" + CONFIG_LOCK_KEY + ")");
    }

    public void restaurant(String businessId) {
        if (enabled()) jdbc.queryForList("select id from business where id = ? for no key update", String.class, businessId);
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
