package com.agafari.com.notifications;

import com.agafari.com.jobs.JobQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AGF SUB-R2-09 (SEC-13, BD-5): owner notices, written in the business transaction and delivered by the
 * {@code notification.send} job. Identity is (restaurant, kind, key, channel), so a repeat creates no second row or job.
 */
@Component
public class NotificationOutbox {

    private final JdbcTemplate jdbc;
    private final JobQueue queue;
    private final ObjectMapper mapper;
    private final Map<NotificationChannel, NotificationSender> senders;

    public NotificationOutbox(JdbcTemplate jdbc, JobQueue queue, ObjectMapper mapper, List<NotificationSender> senders) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.mapper = mapper;
        this.senders = senders.stream().collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
    }

    /** An in-app notice. */
    public boolean add(String businessId, NotificationKind kind, String key, Map<String, Object> templateData) {
        return add(businessId, kind, key, NotificationChannel.IN_APP, templateData);
    }

    /** Adds a PENDING notice and its job; false if this notice already exists. Requires the caller's transaction. */
    public boolean add(String businessId, NotificationKind kind, String key, NotificationChannel channel, Map<String, Object> templateData) {
        requireTransaction();
        if (!senders.containsKey(channel)) throw new NotificationChannelUnavailableException(channel);
        String id = UUID.randomUUID().toString();
        int created = jdbc.update("insert into notification (id, business_id, kind, key, channel, kind_class, template_data) values (?, ?, ?, ?, ?, ?, ?::jsonb) "
                        + "on conflict (business_id, kind, key, channel) do nothing",
                id, businessId, kind.name(), key, channel.name(), kind.kindClass().name(), mapper.writeValueAsString(templateData));
        if (created == 0) return false;
        queue.enqueue(NotificationSendHandler.TYPE, id, new NotificationSendPayload(id, id));
        return true;
    }

    /** SENT → READ, for this restaurant's in-app notice only; false if the notice isn't in that state. */
    public boolean markRead(String businessId, String notificationId) {
        return jdbc.update("update notification set status = 'READ', updated_at = now() where id = ? and business_id = ? and channel = 'IN_APP' and status = 'SENT'",
                notificationId, businessId) == 1;
    }

    /**
     * Controlled retry: FAILED → PENDING with a new job. Refused when a send may already have reached the recipient and
     * repeating it isn't safe.
     */
    public boolean retryFailed(String notificationId) {
        requireTransaction();
        List<Map<String, Object>> rows = jdbc.queryForList("select channel, kind_class, attempts from notification where id = ? and status = 'FAILED'", notificationId);
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.get(0);
        NotificationSender sender = senders.get(NotificationChannel.valueOf((String) row.get("channel")));
        if (sender == null) return false;
        boolean mayRepeat = NotificationSendHandler.mayRepeat(sender, NotificationKindClass.valueOf((String) row.get("kind_class")));
        if (((Number) row.get("attempts")).intValue() > 0 && !mayRepeat) return false;
        if (jdbc.update("update notification set status = 'PENDING', updated_at = now() where id = ? and status = 'FAILED'", notificationId) != 1) return false;
        String jobKey = notificationId + ":retry:" + UUID.randomUUID();
        queue.enqueue(NotificationSendHandler.TYPE, jobKey, new NotificationSendPayload(notificationId, jobKey));
        return true;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("A notice must be changed inside a transaction");
    }
}
