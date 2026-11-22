package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.notifications.NotificationKind;
import com.agafari.com.notifications.NotificationSendHandler;
import com.agafari.com.notifications.NotificationSendPayload;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R5-03-D (assessment story 4; D-R5, N2–N4 in register §69): renewal notices. Reminders on days 0, 3 and 6 of grace
 * at 09:00 in the restaurant's time zone (only the latest one due; never once paid or ended; paused while a payment is under
 * review, I1), "payment received" on recovery, "plan ended" for non-payment, "plan ended, refund pending" for a payment after
 * the end; and the owner's notice list. PostgreSQL; the fake ET provider (SYNTHETIC answers).
 */
class RenewalNoticesTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

    @Autowired private RenewalNotices notices;
    @Autowired private NotificationSendHandler sender;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private ObjectMapper mapper;

    private String priceRef;

    @BeforeEach
    void setUpNotices() {
        evaluator.setPeriodEndEnabled(true);
        priceRef = "price_notice_" + SEQ.incrementAndGet();
        price = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', ?, 93, ?, true, now() - interval '400 days')", price, ET_AMOUNT, priceRef);
        jdbc.update("update business set time_zone = ? where id = ?", ADDIS.getId(), owner.businessId());
    }

    @AfterEach
    void tearDownNotices() {
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    private record Sub(Tenant tenant, String id, String ref, String customer, Instant periodEnd) {
    }

    private Sub provider(Tenant t) {
        String n = SEQ.incrementAndGet() + "_" + t.businessId().substring(0, 6);
        Instant start = micros(clock.now().minus(Duration.ofDays(1))), end = oneMonth(start);
        String c = checkout(t, CheckoutKind.NEW, null), in = "in_n_first_" + n;
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(clock.now()), metadata(c),
                new ProviderBilling(in, "sub_n_" + n, "cus_n_" + n, priceRef, PriceInterval.MONTH, start, end, "pi_n_" + n, null)));
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return new Sub(t, current(t), "sub_n_" + n, "cus_n_" + n, end);
    }

    private String renewal(Sub s, Instant tPay) {
        String in = "in_n_renew_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), tPay, Map.of(),
                ProviderBilling.renewal(in, s.ref(), s.customer(), priceRef, PriceInterval.MONTH, s.periodEnd(), oneMonth(s.periodEnd()), "pi_" + in)));
        return in;
    }

    /** Day {@code k} of grace at 09:00 in {@code zone} (N4); day 0 never before the period end itself. */
    private static Instant reminderAt(Instant periodEnd, ZoneId zone, int k) {
        LocalDate day = periodEnd.atZone(zone).toLocalDate().plusDays(k);
        Instant at = day.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
        return k == 0 && at.isBefore(periodEnd) ? periodEnd : at;
    }

    private void at(Instant t) {
        clock.setOffset(Duration.between(Instant.now(), t));
    }

    private List<String> keys(Tenant t, NotificationKind kind) {
        return jdbc.queryForList("select key from notification where business_id = ? and kind = ? order by created_at", String.class, t.businessId(), kind.name());
    }

    private void deliverAll(Tenant t) {
        for (String id : jdbc.queryForList("select id from notification where business_id = ? and status = 'PENDING'", String.class, t.businessId())) {
            sender.handle(new NotificationSendPayload(id, id));
        }
    }

    private void materialize(Tenant t) {
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.materialize(t.businessId());
        });
    }

    // ---- reminders (days 0, 3, 6 at 09:00 local) ----

    @Test
    void dayZeroThreeAndSixRemindersEachComeOnceAtNineInTheRestaurantsTimeZone() {
        Sub s = provider(owner);
        at(reminderAt(s.periodEnd(), ADDIS, 0).plusSeconds(60));
        notices.plan(clock.now());
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).as("day 0, once").singleElement().asString().endsWith(":d0");

        at(reminderAt(s.periodEnd(), ADDIS, 3).minusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).as("not before 09:00 on day 3").hasSize(1);
        at(reminderAt(s.periodEnd(), ADDIS, 3).plusSeconds(60));
        notices.plan(clock.now());
        at(reminderAt(s.periodEnd(), ADDIS, 6).plusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).hasSize(3).last().asString().endsWith(":d6");

        Map<String, Object> d6 = jdbc.queryForMap("select kind_class, template_data::text as data from notification where business_id = ? and key like '%:d6'", owner.businessId());
        assertThat(d6.get("kind_class")).isEqualTo("PAYMENT_REQUEST");
        JsonNode data = mapper.readTree((String) d6.get("data"));
        assertThat(Instant.parse(data.path("graceUntil").asString())).isEqualTo(s.periodEnd().plus(Duration.ofDays(7)));
        assertThat(data.path("timeZone").asString()).isEqualTo(ADDIS.getId());
        assertThat(data.path("day").asInt()).isEqualTo(6);
    }

    @Test
    void noReminderForARowThatIsNotPastDue() {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(false); // period-end handling off: the stored ACTIVE row is not past due
        at(reminderAt(s.periodEnd(), ADDIS, 3).plusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).isEmpty();
    }

    @Test
    void onlyTheLatestDueReminderIsSentWhenRemindersWereMissed() {
        Sub s = provider(owner);
        at(reminderAt(s.periodEnd(), ADDIS, 6).plusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).singleElement().asString().endsWith(":d6");
    }

    @Test
    void theDueTimeFollowsDaylightSavingInTheRestaurantsZone() {
        ZoneId ny = ZoneId.of("America/New_York");
        Instant pe = Instant.parse("2031-03-07T15:00:00Z"); // DST starts 2031-03-09 in New York
        assertThat(RenewalNotices.reminderAt(pe, ny, 0)).isEqualTo(Instant.parse("2031-03-07T15:00:00Z")); // after 09:00 EST: at the period end
        assertThat(RenewalNotices.reminderAt(pe, ny, 3)).isEqualTo(Instant.parse("2031-03-10T13:00:00Z")); // 09:00 EDT
        assertThat(RenewalNotices.reminderAt(Instant.parse("2031-03-07T12:00:00Z"), ny, 0)).isEqualTo(Instant.parse("2031-03-07T14:00:00Z")); // 09:00 EST
        assertThat(RenewalNotices.reminderAt(pe, null, 3)).as("no time zone recorded: UTC").isEqualTo(Instant.parse("2031-03-10T09:00:00Z"));
    }

    @Test
    void noReminderOncePaidAndPaymentReceivedIsSentOnRecovery() {
        Sub s = provider(owner);
        at(reminderAt(s.periodEnd(), ADDIS, 0).plusSeconds(60));
        notices.plan(clock.now());
        Instant tPay = micros(s.periodEnd().plus(Duration.ofDays(2)));
        at(tPay.plusSeconds(60));
        String in = renewal(s, tPay);
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, in);
        assertThat(keys(owner, NotificationKind.PAYMENT_RECEIVED)).containsExactly("payment:" + paymentId);
        Map<String, Object> received = jdbc.queryForMap("select kind_class from notification where business_id = ? and kind = 'PAYMENT_RECEIVED'", owner.businessId());
        assertThat(received.get("kind_class")).isEqualTo("ESSENTIAL");

        at(reminderAt(s.periodEnd(), ADDIS, 6).plusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).as("paid: no more reminders").hasSize(1);
    }

    @Test
    void aRenewalBeforeThePeriodEndSendsNoPaymentReceived() {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().minus(Duration.ofHours(1)));
        at(tPay.plusSeconds(60));
        process(event(renewal(s, tPay)));
        assertThat(keys(owner, NotificationKind.PAYMENT_RECEIVED)).as("§13: nothing for an ordinary renewal").isEmpty();
    }

    @Test
    void anEndedPlanGetsOnePlanEndedNoticeAndNoMoreReminders() {
        Sub s = provider(owner);
        at(s.periodEnd().plus(Duration.ofDays(8)));
        materialize(owner);
        notices.plan(clock.now());
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.EXPIRY_NOTICE)).containsExactly("ended:" + s.id());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).isEmpty();
    }

    @Test
    void aPaymentAfterThePlanEndedSendsPlanEndedRefund() {
        Sub s = provider(owner);
        at(s.periodEnd().plus(Duration.ofDays(8)));
        materialize(owner);
        String in = renewal(s, micros(clock.now().minusSeconds(60)));
        process(event(in));
        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, in);
        assertThat(keys(owner, NotificationKind.PLAN_ENDED_REFUND)).containsExactly("payment:" + paymentId);
        JsonNode data = mapper.readTree(jdbc.queryForObject("select template_data::text from notification where business_id = ? and kind = 'PLAN_ENDED_REFUND'", String.class,
                owner.businessId()));
        assertThat(data.path("amountMinor").asLong()).isEqualTo(ET_AMOUNT);
        assertThat(data.path("currency").asString()).isEqualTo("ETB");
    }

    @Test
    void remindersPauseWhileAPaymentIsUnderReviewAndEssentialNoticesDont() {
        Sub s = provider(owner);
        jdbc.update("insert into billing_exception (id, business_id, code, involves_customer_funds, facts, status, created_at) values (?, ?, 'FIXTURE_REVIEW', true, '{}'::jsonb, 'OPEN', now())",
                UUID.randomUUID().toString(), owner.businessId());
        at(reminderAt(s.periodEnd(), ADDIS, 0).plusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).as("I1: paused").isEmpty();
        at(s.periodEnd().plus(Duration.ofDays(8)));
        materialize(owner);
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.EXPIRY_NOTICE)).as("essential: never paused").hasSize(1);
    }

    @Test
    void nothingForTrialsOrInactiveMarkets() {
        Sub s = provider(owner);
        Tenant trial = data.tenant("notice-trial");
        startTrial(trial, clock.now().plus(Duration.ofDays(1)));
        outcomes.setBillingEnabled(Market.ET, false);
        at(reminderAt(s.periodEnd(), ADDIS, 3).plusSeconds(60));
        notices.plan(clock.now());
        assertThat(keys(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, trial.businessId())).isZero();
    }

    // ---- the owner's notice list (N2) ----

    @Test
    void theOwnerListsOnlyTheirOwnDeliveredNoticesAndMarksThemRead() throws Exception {
        Sub s = provider(owner);
        Tenant other = data.tenant("notice-other");
        jdbc.update("update business set time_zone = ? where id = ?", ADDIS.getId(), other.businessId()); // same zone, so both are due now
        Sub o = provider(other);
        at(reminderAt(s.periodEnd(), ADDIS, 0).plus(Duration.ofHours(1)));
        notices.plan(clock.now());
        assertThat(keys(other, NotificationKind.RENEWAL_PAYMENT_REQUEST)).as("the other restaurant has its own reminder").hasSize(1);

        JsonNode before = mapper.readTree(mockMvc.perform(get("/api/plan/notices").header("Authorization", data.bearer(owner))).andReturn().getResponse().getContentAsString());
        assertThat(before.size()).as("not shown before delivery").isZero();
        deliverAll(owner);
        deliverAll(other);

        JsonNode list = mapper.readTree(mockMvc.perform(get("/api/plan/notices").header("Authorization", data.bearer(owner))).andReturn().getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(1);
        JsonNode n = list.get(0);
        assertThat(n.path("kind").asString()).isEqualTo("RENEWAL_PAYMENT_REQUEST");
        assertThat(n.path("read").asBoolean()).isFalse();
        assertThat(n.path("data").path("day").asInt()).isZero();
        assertThat(n.has("businessId")).isFalse();

        String mine = n.path("id").asString();
        String theirs = jdbc.queryForObject("select id from notification where business_id = ?", String.class, other.businessId());
        assertThat(mockMvc.perform(post("/api/plan/notices/{id}/read", theirs).header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus())
                .as("another restaurant's notice").isEqualTo(404);
        assertThat(mockMvc.perform(post("/api/plan/notices/{id}/read", mine).header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus()).isEqualTo(204);
        JsonNode after = mapper.readTree(mockMvc.perform(get("/api/plan/notices").header("Authorization", data.bearer(owner))).andReturn().getResponse().getContentAsString());
        assertThat(after.get(0).path("read").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select status from notification where id = ?", String.class, theirs)).isEqualTo("SENT");
        assertThat(o.id()).isNotNull();
    }

    @Test
    void theNoticeListNeedsAnOwner() throws Exception {
        assertThat(mockMvc.perform(get("/api/plan/notices")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
