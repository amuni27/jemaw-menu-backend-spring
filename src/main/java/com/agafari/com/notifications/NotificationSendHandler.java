package com.agafari.com.notifications;

import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.TransientJobException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AGF SUB-R2-09: delivers one notice. {@code attempts} counts sends that may have reached the recipient: it is committed
 * before the send and taken back only when the sender knows nothing left. A PENDING row with attempts therefore means
 * "possibly sent", and it isn't sent again unless repeating is safe. Every status write is conditional on PENDING, so a
 * stale worker can't change a finished notice.
 */
@Slf4j
@Component
public class NotificationSendHandler implements JobHandler<NotificationSendPayload> {

    public static final String TYPE = "notification.send";

    private final JdbcTemplate jdbc;
    private final Map<NotificationChannel, NotificationSender> senders;
    private final PaymentReviewCheck review;

    /** Without billing: nothing is ever under review. */
    public NotificationSendHandler(JdbcTemplate jdbc, List<NotificationSender> senders) {
        this(jdbc, senders, PaymentReviewCheck.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationSendHandler(JdbcTemplate jdbc, List<NotificationSender> senders, PaymentReviewCheck review) {
        this.review = review;
        this.jdbc = jdbc;
        this.senders = senders.stream().collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
    }

    /** May a notice that possibly left be sent again? An external payment request never is. */
    static boolean mayRepeat(NotificationSender sender, NotificationKindClass kindClass) {
        return sender.retrySafeAfterUncertainOutcome() && (sender.channel() == NotificationChannel.IN_APP || kindClass == NotificationKindClass.ESSENTIAL);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<NotificationSendPayload> payloadType() {
        return NotificationSendPayload.class;
    }

    @Override
    public void handle(NotificationSendPayload payload) {
        ProviderCallGuard.assertNoTransaction(); // a programming error, so outside the delivery outcomes below
        String id = payload.notificationId();
        List<Map<String, Object>> rows = jdbc.queryForList("select business_id, kind, key, channel, kind_class, status, template_data::text as data from notification where id = ?", id);
        if (rows.isEmpty()) throw new PermanentJobException("Notification " + id + " doesn't exist", null);
        Map<String, Object> row = rows.get(0);
        if (!"PENDING".equals(row.get("status"))) return;
        NotificationChannel channel = NotificationChannel.valueOf((String) row.get("channel"));
        NotificationSender sender = senders.get(channel);
        if (sender == null) {
            fail(id, "no_sender");
            return;
        }
        NotificationKindClass kindClass = NotificationKindClass.valueOf((String) row.get("kind_class"));
        // AGF SUB-R4-11 (I1): a payment request queued before a review isn't sent during it. Nothing left, so it stays retryable.
        if (kindClass == NotificationKindClass.PAYMENT_REQUEST && review.underReview((String) row.get("business_id"))) {
            fail(id, "paused_under_review");
            return;
        }
        boolean mayRepeat = mayRepeat(sender, kindClass);
        if (jdbc.update("update notification set attempts = attempts + 1, updated_at = now() where id = ? and status = 'PENDING' and (attempts = 0 or ?)", id, mayRepeat) == 0) {
            fail(id, "possibly_sent");
            return;
        }
        NotificationMessage message = new NotificationMessage(id, (String) row.get("business_id"), (String) row.get("kind"), (String) row.get("key"), channel, kindClass, (String) row.get("data"));
        try {
            sender.deliver(message);
        } catch (NotificationNotSentException e) {
            jdbc.update("update notification set attempts = attempts - 1, updated_at = now() where id = ? and status = 'PENDING' and attempts > 0", id);
            if (!e.isRetryable()) {
                fail(id, "rejected");
                return;
            }
            retry(id, payload, e);
        } catch (Exception e) {
            if (!mayRepeat) {
                fail(id, "uncertain");
                return;
            }
            retry(id, payload, e);
        }
        jdbc.update("update notification set status = 'SENT', updated_at = now() where id = ? and status = 'PENDING'", id);
    }

    private void retry(String id, NotificationSendPayload payload, Exception cause) {
        if (isLastRun(payload.jobKey())) fail(id, "retries_exhausted");
        throw new TransientJobException("Notification " + id + " wasn't delivered", cause);
    }

    /** Whether the runner will end this job after a transient failure now, by its own rules. */
    private boolean isLastRun(String jobKey) {
        List<Map<String, Object>> jobs = jdbc.queryForList("select claims, first_attempt_at, now() as db_now from job where type = ? and key = ?", TYPE, jobKey);
        if (jobs.isEmpty()) return false;
        Map<String, Object> job = jobs.get(0);
        return JobFailurePolicy.decide(JobFailurePolicy.Kind.TRANSIENT, ((Number) job.get("claims")).intValue(), ((Timestamp) job.get("first_attempt_at")).toInstant(),
                ((Timestamp) job.get("db_now")).toInstant(), JobFailurePolicy.transientCap(TYPE, transientCap())).dead();
    }

    private void fail(String id, String reason) {
        if (jdbc.update("update notification set status = 'FAILED', updated_at = now() where id = ? and status = 'PENDING'", id) == 1) {
            log.warn("event=notification.failed id={} reason={}", id, reason);
        }
    }
}
