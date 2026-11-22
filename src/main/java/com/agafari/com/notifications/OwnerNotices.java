package com.agafari.com.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * AGF SUB-R5-03-D (N2, register §69): the owner's in-app notice list. Only the restaurant's own delivered ({@code SENT}) and
 * read notices; never a pending, failed or e-mail row. Notices exist only on PostgreSQL (V21), so elsewhere the list is empty.
 */
@Component
public class OwnerNotices {

    private static final int LIMIT = 50;

    /** @param data the notice's template data (plan, dates, amounts, time zone; no personal data) */
    public record Notice(String id, String kind, boolean read, Instant createdAt, JsonNode data) {
    }

    private final JdbcTemplate jdbc;
    private final NotificationOutbox outbox;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private volatile Boolean postgres;

    public OwnerNotices(JdbcTemplate jdbc, NotificationOutbox outbox, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
    }

    public List<Notice> list(String businessId) {
        if (!onPostgres()) return List.of();
        return jdbc.query("select id, kind, status, created_at, template_data::text as data from notification where business_id = ? and channel = 'IN_APP' "
                        + "and status in ('SENT', 'READ') order by created_at desc, id desc limit ?",
                (rs, n) -> new Notice(rs.getString("id"), rs.getString("kind"), "READ".equals(rs.getString("status")), ((Timestamp) rs.getTimestamp("created_at")).toInstant(),
                        mapper.readTree(rs.getString("data"))), businessId, LIMIT);
    }

    /** SENT → READ for the restaurant's own notice; false if it isn't one, or is already read. */
    public boolean markRead(String businessId, String notificationId) {
        if (!onPostgres()) return false;
        if (jdbc.queryForObject("select count(*) from notification where id = ? and business_id = ? and channel = 'IN_APP' and status = 'READ'", Integer.class,
                notificationId, businessId) == 1) return true; // idempotent
        return Boolean.TRUE.equals(transaction.execute(s -> outbox.markRead(businessId, notificationId)));
    }

    private boolean onPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                    c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
            postgres = known;
        }
        return known;
    }
}
