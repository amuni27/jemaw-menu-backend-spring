package com.agafari.com.billing;

import com.agafari.com.billing.BusinessCalendar.YearRules;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.support.MonetizationFixtures;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AGF SUB-R4-12: deadlines of real financial exceptions on PostgreSQL: set at creation (I2, G1, G4), recomputed after
 * audited calendar changes (R-6, I4), and flagged overdue once by the hourly sweep (R-3). Grace values are fixtures only;
 * AD-7 is open and no production grace exists.
 */
class ExceptionSlaTest extends PaymentOutcomeTestBase {

    private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Set<DayOfWeek> SAT_SUN = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    @Autowired private ExceptionService exceptionService;
    @Autowired private ExceptionSla sla;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext context;

    private int year;

    @BeforeEach
    void publishThisYear() {
        year = LocalDate.now(ADDIS).getYear();
        for (int y = year - 1; y <= year + 1; y++) {
            for (String m : List.of("ET", "US")) {
                jdbc.update("insert into market_calendar_year (market, year, published) values (?, ?, true) on conflict (market, year) do update set published = true, weekend_days = 'SATURDAY,SUNDAY'", m, y);
            }
        }
    }

    private Instant soon() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    private static YearRules rules(LocalDate... holidays) {
        return new YearRules(SAT_SUN, Set.of(holidays), true);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from billing_exception where id = ?", id);
    }

    private static Instant instant(Object ts) {
        return ((Timestamp) ts).toInstant();
    }

    private String row6(Tenant t) {
        String c = checkout(t, CheckoutKind.NEW, null);
        MonetizationFixtures.subscription(jdbc, t.businessId(), "MODEL_4", 1, admin.getId());
        process(event(paid(c, soon())));
        return jdbc.queryForObject("select id from billing_exception where business_id = ? and code = 'STATE_CHANGED'", String.class, t.businessId());
    }

    private JsonNode sla(String id) {
        return mapper.readTree(row(id).get("facts").toString()).path("sla");
    }

    // ---- set at creation ----

    @Test
    void aCustomerFundsExceptionGetsItsDeadlineWhenCreatedInTheRestaurantsZone() {
        String id = row6(owner);
        Map<String, Object> r = row(id);

        Instant expected = BusinessCalendar.twoBusinessDaysAfter(instant(r.get("created_at")), ADDIS, y -> rules()).due();
        assertThat(instant(r.get("due_at"))).isEqualTo(expected);
        assertThat(sla(id).path("immediate").asBoolean()).isFalse();
        assertThat(sla(id).path("calendarIncomplete").asBoolean()).isFalse();
        assertThat(sla(id).path("zone").asString()).isEqualTo("Africa/Addis_Ababa");
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_OPENED'", String.class, id)).contains("due=");
    }

    @Test
    void g4APaidRowWithoutRecordedGraceUsesTheSlaAndKeepsTheGraceGapVisible() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        process(event(paid(c, soon())));
        assertThat(jdbc.queryForObject("select grace_days from business_subscription where business_id = ? and source = 'PROVIDER'", Integer.class, owner.businessId())).isNull();
        process(event(paid(c, soon().plusSeconds(1)))); // row 2: a duplicate payment, AUTO_HANDLING
        String id = jdbc.queryForObject("select id from billing_exception where business_id = ?", String.class, owner.businessId());

        Instant expected = BusinessCalendar.twoBusinessDaysAfter(instant(row(id).get("created_at")), ADDIS, y -> rules()).due();
        assertThat(instant(row(id).get("due_at"))).as("not period_end − 24 h: null grace isn't zero grace").isEqualTo(expected);
        assertThat(sla(id).path("graceUnknown").asBoolean()).isTrue();
        assertThat(sla(id).path("graceUntil").isNull() || sla(id).path("graceUntil").isMissingNode()).isTrue();
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_OPENED'", String.class, id)).contains("graceUnknown=true");
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 10})
    void ac2RecordedGraceBringsTheDeadlineForwardOrMakesItImmediate(int hoursOfGraceLeft) {
        enableModel4Since(Instant.now().minus(Duration.ofDays(60)));
        // explicit fixture grace recorded on the row (one day, or zero days so the paid period still lies ahead); grace ends in hoursOfGraceLeft
        int graceDays = hoursOfGraceLeft >= 24 ? 1 : 0;
        Instant periodEnd = micros(Instant.now().plus(Duration.ofHours(hoursOfGraceLeft - 24L * graceDays)));
        Instant tPay = periodEnd.atZone(java.time.ZoneOffset.UTC).minusMonths(1).toInstant();
        String c = checkout(owner, CheckoutKind.NEW, null);
        process(event(paid(c, tPay)));
        String sub = current(owner);
        assertThat(jdbc.queryForObject("select period_end from business_subscription where id = ?", Timestamp.class, sub).toInstant()).isEqualTo(periodEnd);
        jdbc.update("update business_subscription set grace_days = ? where id = ?", graceDays, sub);
        process(event(paid(c, soon()))); // row 2: under review
        String id = jdbc.queryForObject("select id from billing_exception where business_id = ?", String.class, owner.businessId());
        Map<String, Object> r = row(id);

        if (hoursOfGraceLeft == 30) {
            assertThat(instant(r.get("due_at"))).as("graceUntil − 24 h").isEqualTo(periodEnd);
            assertThat(sla(id).path("immediate").asBoolean()).isFalse();
        } else {
            assertThat(instant(r.get("due_at"))).as("due now").isEqualTo(instant(r.get("created_at")));
            assertThat(sla(id).path("immediate").asBoolean()).isTrue();
            assertThat(adminView(id).path("immediate").asBoolean()).as("derived for the admin").isTrue();
        }
        assertThat(sla(id).path("graceUnknown").asBoolean()).isFalse();
        assertThat(Instant.parse(sla(id).path("graceUntil").asString())).isEqualTo(periodEnd.plus(Duration.ofDays(graceDays)));
    }

    @Test
    void aUsRestaurantsDeadlineFollowsItsOwnZoneAndTheUsCalendar() {
        Tenant us = usTenant("sla-us", "America/Chicago");
        LocalDate etOnlyHoliday = nextBusinessDate(Instant.now(), CHICAGO);
        jdbc.update("insert into market_calendar_day (market, calendar_date, kind, name) values ('ET', ?, 'PUBLIC_HOLIDAY', 'ET only')", etOnlyHoliday);

        String id = createDirect(us);
        Instant expected = BusinessCalendar.twoBusinessDaysAfter(instant(row(id).get("created_at")), CHICAGO, y -> rules()).due();

        assertThat(instant(row(id).get("due_at"))).as("an Ethiopian holiday doesn't move a US deadline").isEqualTo(expected);
        assertThat(sla(id).path("zone").asString()).isEqualTo("America/Chicago");
        assertThat(sla(id).path("market").asString()).isEqualTo("US");
    }

    @Test
    void ac3AnUnpublishedYearUsesWeekendsOnlyAndAlertsOncePerMarketYear() {
        jdbc.update("update market_calendar_year set published = false where market = 'ET'");
        LocalDate holiday = nextBusinessDate(Instant.now(), ADDIS);
        jdbc.update("insert into market_calendar_day (market, calendar_date, kind, name) values ('ET', ?, 'PUBLIC_HOLIDAY', 'Not yet published')", holiday);

        String id = row6(owner);
        Instant expected = BusinessCalendar.twoBusinessDaysAfter(instant(row(id).get("created_at")), ADDIS, y -> new YearRules(SAT_SUN, Set.of(), false)).due();

        assertThat(instant(row(id).get("due_at"))).isEqualTo(expected);
        assertThat(sla(id).path("calendarIncomplete").asBoolean()).isTrue();
        assertThat(adminView(id).path("calendarIncomplete").asBoolean()).isTrue();

        sla.sweep(Instant.now());
        sla.sweep(Instant.now());
        assertThat(count("select count(*) from admin_audit_event where action = 'MARKET_CALENDAR_INCOMPLETE' and target_type = 'MARKET_CALENDAR'")).as("one alert, not one per hour").isEqualTo(1);
    }

    // ---- AC4: recompute after a calendar change ----

    @Test
    void ac4AddingAndRemovingAHolidayRecomputesOpenDeadlinesAndIsAudited() throws Exception {
        String open = row6(owner);
        Tenant other = data.tenant("sla-resolved");
        String resolved = row6(other);
        jdbc.update("update billing_exception set status = 'RESOLVED', resolution = 'NO_ACTION_REQUIRED', resolved_by = ?, resolved_at = now() where id = ?", admin.getId(), resolved);
        Tenant third = data.tenant("sla-escalated");
        String escalated = row6(third);
        jdbc.update("update billing_exception set status = 'ESCALATED', escalated_at = now(), escalation_reason = 'r' where id = ?", escalated);
        String us = createDirect(usTenant("sla-us-untouched", "America/New_York"));
        Map<String, Object> before = Map.of("resolved", row(resolved).get("due_at"), "escalated", row(escalated).get("due_at"), "us", row(us).get("due_at"));
        Instant created = instant(row(open).get("created_at"));
        LocalDate holiday = nextBusinessDate(created, ADDIS);

        MockHttpServletResponse added = call(post("/api/admin/billing/calendar/ET/holidays"), "{\"date\":\"" + holiday + "\",\"name\":\"Test holiday\",\"sourceNote\":\"Official list\"}");

        assertThat(added.getStatus()).isEqualTo(201);
        Instant withHoliday = BusinessCalendar.twoBusinessDaysAfter(created, ADDIS, y -> rules(holiday)).due();
        assertThat(instant(row(open).get("due_at"))).isEqualTo(withHoliday);
        assertThat(mapper.readTree(added.getContentAsString()).path("recomputed").asInt()).isEqualTo(1);
        assertThat(row(resolved).get("due_at")).isEqualTo(before.get("resolved"));
        assertThat(row(escalated).get("due_at")).isEqualTo(before.get("escalated"));
        assertThat(row(us).get("due_at")).as("another market").isEqualTo(before.get("us"));
        assertAudited("MARKET_HOLIDAY_ADDED", "ET-" + holiday);
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_DUE_RECOMPUTED' and actor_user_id = ?",
                String.class, open, admin.getId())).contains("->");

        MockHttpServletResponse removed = call(delete("/api/admin/billing/calendar/ET/holidays/" + holiday), null);

        assertThat(removed.getStatus()).isEqualTo(200);
        assertThat(instant(row(open).get("due_at"))).isEqualTo(BusinessCalendar.twoBusinessDaysAfter(created, ADDIS, y -> rules()).due());
        assertAudited("MARKET_HOLIDAY_REMOVED", "ET-" + holiday);
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_DUE_RECOMPUTED'", open)).isEqualTo(2);
    }

    @Test
    void aCalendarChangeThatMovesNoDeadlineRecordsNoRecompute() throws Exception {
        String open = row6(owner);
        LocalDate farAway = LocalDate.of(year + 1, 6, 15);

        MockHttpServletResponse r = call(post("/api/admin/billing/calendar/ET/holidays"), "{\"date\":\"" + farAway + "\",\"name\":\"Later\"}");

        assertThat(mapper.readTree(r.getContentAsString()).path("recomputed").asInt()).isZero();
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_DUE_RECOMPUTED'", open)).isZero();
    }

    // ---- AC5 ----

    @Test
    void ac5WeekendDaysAreConfigurableAndRecomputeDeadlines() throws Exception {
        String open = row6(owner);
        Instant created = instant(row(open).get("created_at"));
        int createdYear = created.atZone(ADDIS).getYear();

        MockHttpServletResponse r = call(put("/api/admin/billing/calendar/ET/" + createdYear), "{\"published\":true,\"weekendDays\":[\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}");

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(r.getContentAsString()).path("weekendDays").toString()).contains("FRIDAY", "SATURDAY", "SUNDAY");
        Instant expected = BusinessCalendar.twoBusinessDaysAfter(created, ADDIS,
                y -> new YearRules(y == createdYear ? Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) : SAT_SUN, Set.of(), true)).due();
        assertThat(instant(row(open).get("due_at"))).isEqualTo(expected);
        assertAudited("MARKET_CALENDAR_YEAR_UPDATED", "ET-" + createdYear);
        assertThat(call(get("/api/admin/billing/calendar/ET/" + createdYear), null).getContentAsString()).contains("FRIDAY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"all-days-weekend", "unknown-day", "bad-year", "bad-market", "holiday-without-name", "bad-date", "duplicate-holiday", "remove-missing"})
    void calendarInputIsValidated(String variant) throws Exception {
        LocalDate d = LocalDate.of(year, 12, 24);
        if (variant.equals("duplicate-holiday")) jdbc.update("insert into market_calendar_day (market, calendar_date, kind, name) values ('ET', ?, 'PUBLIC_HOLIDAY', 'x')", d);
        MockHttpServletResponse r = switch (variant) {
            case "all-days-weekend" -> call(put("/api/admin/billing/calendar/ET/" + year),
                    "{\"published\":true,\"weekendDays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}");
            case "unknown-day" -> call(put("/api/admin/billing/calendar/ET/" + year), "{\"published\":true,\"weekendDays\":[\"FUNDAY\"]}");
            case "bad-year" -> call(put("/api/admin/billing/calendar/ET/1999"), "{\"published\":true,\"weekendDays\":[\"SUNDAY\"]}");
            case "bad-market" -> call(post("/api/admin/billing/calendar/KE/holidays"), "{\"date\":\"" + d + "\",\"name\":\"x\"}");
            case "holiday-without-name" -> call(post("/api/admin/billing/calendar/ET/holidays"), "{\"date\":\"" + d + "\"}");
            case "bad-date" -> call(post("/api/admin/billing/calendar/ET/holidays"), "{\"date\":\"24/12\",\"name\":\"x\"}");
            case "duplicate-holiday" -> call(post("/api/admin/billing/calendar/ET/holidays"), "{\"date\":\"" + d + "\",\"name\":\"x\"}");
            default -> call(delete("/api/admin/billing/calendar/ET/holidays/" + LocalDate.of(year, 12, 23)), null);
        };

        int expected = switch (variant) {
            case "bad-market", "remove-missing" -> 404;
            case "duplicate-holiday" -> 409;
            default -> 400;
        };
        assertThat(r.getStatus()).isEqualTo(expected);
        assertThat(count("select count(*) from admin_audit_event where action like 'MARKET_%' and outcome = 'SUCCESS'")).as("nothing changed").isZero();
    }

    @Test
    void anOwnerTokenCantReadOrChangeTheCalendar() throws Exception {
        int read = mockMvc.perform(get("/api/admin/billing/calendar/ET/" + year).header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus();
        int write = mockMvc.perform(post("/api/admin/billing/calendar/ET/holidays").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + year + "-12-24\",\"name\":\"x\"}")
                .header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus();

        assertThat(read).isEqualTo(403);
        assertThat(write).isEqualTo(403);
        assertThat(count("select count(*) from market_calendar_day")).isZero();
    }

    // ---- overdue (R-3) ----

    @Test
    void theSweepFlagsEachOverdueDeadlineOnceIncludingAutomaticRefunds() throws Exception {
        String open = row6(owner);
        Tenant refundTenant = data.tenant("sla-auto");
        Instant expires = micros(Instant.now().plus(1, ChronoUnit.HOURS));
        process(event(paid(checkout(refundTenant, CheckoutKind.NEW, null, price, ET_AMOUNT, expires), expires.plusSeconds(1)))); // row 3: AUTO_HANDLING
        String auto = jdbc.queryForObject("select id from billing_exception where business_id = ?", String.class, refundTenant.businessId());
        String resolved = row6(data.tenant("sla-done"));
        String escalated = row6(data.tenant("sla-esc"));
        jdbc.update("update billing_exception set due_at = now() - interval '1 hour'"); // every deadline has passed
        jdbc.update("update billing_exception set status = 'RESOLVED', resolution = 'NO_ACTION_REQUIRED', resolved_by = ?, resolved_at = now() where id = ?", admin.getId(), resolved);
        jdbc.update("update billing_exception set status = 'ESCALATED', escalated_at = now(), escalation_reason = 'r' where id = ?", escalated);

        assertThat(sla.sweep(Instant.now())).isEqualTo(2);
        assertThat(sla.sweep(Instant.now())).as("no duplicate hourly record").isZero();

        assertThat(overdueAudits(open)).isEqualTo(1);
        assertThat(overdueAudits(auto)).isEqualTo(1);
        assertThat(overdueAudits(resolved)).isZero();
        assertThat(overdueAudits(escalated)).isZero();
        assertThat(adminView(open).path("overdue").asBoolean()).isTrue();
        assertThat(adminView(escalated).path("overdue").asBoolean()).isFalse();
        int status = mockMvc.perform(post("/api/admin/billing/exceptions/" + auto + "/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":" + row(auto).get("row_version") + ",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"n\"}")
                .header("Authorization", data.adminBearer(admin))).andReturn().getResponse().getStatus();
        assertThat(status).as("Q1: an overdue automatic refund still can't be resolved by hand").isEqualTo(409);
    }

    @Test
    void aDeadlineThatMovesAndPassesAgainIsFlaggedAgain() {
        String open = row6(owner);
        jdbc.update("update billing_exception set due_at = now() - interval '2 hours' where id = ?", open);
        sla.sweep(Instant.now());
        jdbc.update("update billing_exception set due_at = now() - interval '1 hour' where id = ?", open);

        assertThat(sla.sweep(Instant.now())).isEqualTo(1);
        assertThat(overdueAudits(open)).isEqualTo(2);
    }

    @Test
    void twoSweepsAtOnceFlagOnce() throws Exception {
        String open = row6(owner);
        jdbc.update("update billing_exception set due_at = now() - interval '1 hour' where id = ?", open);

        List<Integer> flagged = race(2, i -> () -> sla.sweep(Instant.now()));

        assertThat(flagged.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(overdueAudits(open)).isEqualTo(1);
    }

    @Test
    void theHourlySweepIsAJobAndItsTriggerExistsOnlyWithJobsOn() {
        assertThat(context.getBeansOfType(JobHandler.class).values()).anyMatch(h -> ((JobHandler<?>) h).type().equals(ExceptionSlaJob.TYPE));
        assertThat(context.getBeanNamesForType(ExceptionSlaCadence.class)).isEmpty();
    }

    // ---- helpers ----

    private int overdueAudits(String id) {
        return count("select count(*) from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_OVERDUE'", id);
    }

    private JsonNode adminView(String id) {
        try {
            return mapper.readTree(mockMvc.perform(get("/api/admin/billing/exceptions/" + id).header("Authorization", data.adminBearer(admin))).andReturn().getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletResponse call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body) throws Exception {
        if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(b.header("Authorization", data.adminBearer(admin))).andReturn().getResponse();
    }

    private void assertAudited(String action, String target) {
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = ? and target_id = ? and actor_user_id = ? and occurred_at is not null",
                Integer.class, action, target, admin.getId())).isEqualTo(1);
    }

    private Tenant usTenant(String label, String zone) {
        Tenant t = data.tenant(label);
        jdbc.update("update business set country = 'US', time_zone = ? where id = ?", zone, t.businessId());
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, 'US', 'FAKE_US')", UUID.randomUUID().toString(), t.businessId());
        return t;
    }

    /** A customer-funds exception written through the one writer, as reconciliation or a later story would. */
    private String createDirect(Tenant t) {
        String event = UUID.randomUUID().toString();
        jdbc.update("insert into provider_event (id, provider, event_id, type, payload, status) values (?, 'FAKE_US', ?, 't', '{}'::jsonb, 'PROCESSED')", event, "evt-" + event);
        new TransactionTemplate(transactions).executeWithoutResult(s -> exceptionService.create(t.businessId(), "VERIFICATION_FAILED", null, null, event, null, 1000L, "USD",
                Instant.now(), true, "OPEN", Map.of("source", "test"), Instant.now()));
        return jdbc.queryForObject("select id from billing_exception where business_id = ?", String.class, t.businessId());
    }

    private static LocalDate nextBusinessDate(Instant from, ZoneId zone) {
        LocalDate d = from.atZone(zone).toLocalDate().plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d;
    }

    private void enableModel4Since(Instant from) {
        int version = jdbc.queryForObject("select max(version) + 1 from monetization_config", Integer.class);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, false, true, ?, now(), ?)", version, Timestamp.from(from), admin.getId());
    }

    @SuppressWarnings("unused")
    private static Set<LocalDate> none() {
        return new HashSet<>();
    }

    @SuppressWarnings("unused")
    private User adminUser() {
        return admin;
    }
}
