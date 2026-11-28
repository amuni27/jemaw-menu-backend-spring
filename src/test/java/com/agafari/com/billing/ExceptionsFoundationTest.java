package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF EX-F (register §91; PO F-1–F-3): the exceptions foundation.
 * <ul>
 *   <li>A platform exception (no restaurant) is allowed only for the platform codes. It is linked to no restaurant record
 *   and identified durably by its provider event or its source job.</li>
 *   <li>A source job that names a restaurant forces that restaurant (attribution and tenant isolation are never weakened).</li>
 *   <li>Platform deadlines are 24 elapsed hours (G3-E).</li>
 *   <li>The list, the distinct summary counts and the per-exception history are Super Admin only.</li>
 *   <li>Every new exception is logged for alerting (G3-B).</li>
 * </ul>
 * No writer of the platform codes exists yet (gates 3 and 2); rows are made here through the service, or raw SQL for the
 * database rules.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExceptionsFoundationTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private ExceptionService exceptions;
    @Autowired private ExceptionSla sla;
    @Autowired private ObjectMapper mapper;

    // ---- fixtures ----

    private String providerEvent() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into provider_event (id, provider, event_id, type, payload, status) values (?, ?, ?, 'invoice.paid', '{}'::jsonb, 'QUARANTINED')",
                id, PROVIDER, "evt_exf_" + SEQ.incrementAndGet());
        return id;
    }

    private String deadJob(String type, String payloadJson) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into job (id, type, key, payload, payload_hash, status, claims, finished_at, last_error, last_error_kind) "
                + "values (?, ?, ?, ?::jsonb, ?, 'DEAD', 5, now(), 'synthetic failure', 'PERMANENT')", id, type, "exf-" + SEQ.incrementAndGet(), payloadJson, "h" + SEQ.get());
        return id;
    }

    private boolean create(String businessId, String code, String checkoutId, String eventId, String jobId) {
        return exceptions.create(businessId, code, null, checkoutId, eventId, null, 2500L, "ETB", clock.now(), true, "OPEN", Map.of("synthetic", true), clock.now(), jobId);
    }

    private String idOf(String column, String value, String code) {
        return jdbc.queryForObject("select id from billing_exception where " + column + " = ? and code = ?", String.class, value, code);
    }

    private void rawInsert(String businessId, String code, String checkoutId, String eventId, String jobId) {
        jdbc.update("insert into billing_exception (id, business_id, code, checkout_id, provider_event_id, source_job_id, involves_customer_funds, facts, status, created_at) "
                + "values (?, ?, ?, ?, ?, ?, false, '{}'::jsonb, 'OPEN', now())", UUID.randomUUID().toString(), businessId, code, checkoutId, eventId, jobId);
    }

    // ---- F-1: the platform form, in the database ----

    @Test
    void aPlatformExceptionNeedsAPlatformCodeAndADurableSourceAndNoRestaurantLinks() {
        rawInsert(null, "UNATTRIBUTED_PAYMENT", null, providerEvent(), null);
        rawInsert(null, "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, deadJob("inbox.process", "{\"providerEventId\":\"pe-1\"}"));

        assertThatThrownBy(() -> rawInsert(null, PaymentOutcome.STATE_CHANGED, null, providerEvent(), null)).as("not a platform code").hasMessageContaining("ck_billing_exception_platform");
        assertThatThrownBy(() -> rawInsert(null, "UNATTRIBUTED_PAYMENT", null, null, null)).as("no durable source").hasMessageContaining("ck_billing_exception_platform");
        String c = checkout(owner, CheckoutKind.NEW, null);
        assertThatThrownBy(() -> rawInsert(null, "UNATTRIBUTED_PAYMENT", c, providerEvent(), null)).as("a restaurant's checkout").hasMessageContaining("platform exception can't link");
        assertThatThrownBy(() -> rawInsert(owner.businessId(), "UNATTRIBUTED_PAYMENT", null, providerEvent(), null)).as("unattributed means no restaurant")
                .hasMessageContaining("ck_billing_exception_unattributed");
    }

    @Test
    void aSourceJobThatNamesARestaurantForcesThatRestaurant() {
        Tenant other = data.tenant("exf-other");
        String c = checkout(owner, CheckoutKind.NEW, null);
        String job = deadJob("checkout.create", "{\"checkoutId\":\"" + c + "\"}");
        assertThatThrownBy(() -> rawInsert(null, "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, job)).as("attribution is known: never a platform row")
                .hasMessageContaining("source job belongs to a restaurant");
        assertThatThrownBy(() -> rawInsert(other.businessId(), "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, job)).as("never another restaurant")
                .hasMessageContaining("source job belongs to a restaurant");
        rawInsert(owner.businessId(), "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, job);

        String byBusiness = deadJob("period.end", "{\"businessId\":\"" + owner.businessId() + "\"}");
        assertThatThrownBy(() -> rawInsert(other.businessId(), "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, byBusiness)).hasMessageContaining("source job belongs to a restaurant");
    }

    @Test
    void oneExceptionPerSourceJobAndCodeAndTheSourceNeverChanges() {
        String job = deadJob("inbox.process", "{\"providerEventId\":\"pe-2\"}");
        assertThat(create(null, "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, job)).isTrue();
        assertThat(create(null, "PAYMENT_OUTCOME_PENDING_REVIEW", null, null, job)).as("idempotent per job and code").isFalse();
        assertThat(count("select count(*) from billing_exception where source_job_id = ?", job)).isEqualTo(1);
        String id = idOf("source_job_id", job, "PAYMENT_OUTCOME_PENDING_REVIEW");
        String another = deadJob("inbox.process", "{\"providerEventId\":\"pe-3\"}");
        assertThatThrownBy(() -> jdbc.update("update billing_exception set source_job_id = ? where id = ?", another, id)).hasMessageContaining("financial facts can't be changed");
        assertThatThrownBy(() -> jdbc.update("update billing_exception set business_id = ? where id = ?", owner.businessId(), id)).hasMessageContaining("financial facts can't be changed");
    }

    @Test
    void restaurantRowsKeepTheirIsolation() {
        Tenant other = data.tenant("exf-iso");
        String c = checkout(owner, CheckoutKind.NEW, null);
        assertThatThrownBy(() -> rawInsert(other.businessId(), PaymentOutcome.STATE_CHANGED, c, providerEvent(), null)).hasMessageContaining("linked records must belong");
    }

    // ---- SLA (G3-E) and alerting (G3-B) ----

    @Test
    void aPlatformDeadlineIsTwentyFourElapsedHoursAndTheSweepFlagsIt(CapturedOutput output) throws Exception {
        String event = providerEvent();
        Instant before = clock.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(create(null, "UNATTRIBUTED_PAYMENT", null, event, null)).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select * from billing_exception where provider_event_id = ?", event);
        Instant created = ((Timestamp) row.get("created_at")).toInstant(), due = ((Timestamp) row.get("due_at")).toInstant();
        assertThat(created).isAfterOrEqualTo(before);
        assertThat(Duration.between(created, due)).isEqualTo(Duration.ofHours(24));
        assertThat(mapper.readTree(row.get("facts").toString()).path("slaBasis").asString()).contains("24 elapsed hours");
        assertThat(output.getAll()).contains("ERROR").contains("event=billing.exception_opened id=" + row.get("id")).contains("code=UNATTRIBUTED_PAYMENT").contains("restaurant=none");

        String id = (String) row.get("id");
        sla.sweep(due.minusSeconds(1));
        assertThat(count("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_OVERDUE' and target_id = ?", id)).isZero();
        sla.sweep(due);
        assertThat(count("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_OVERDUE' and target_id = ?", id)).isEqualTo(1);
        assertThat(sla.recompute(Market.ET, admin.getId(), "test")).as("calendar changes never move a platform deadline").isZero();
        assertThat(((Timestamp) jdbc.queryForObject("select due_at from billing_exception where id = ?", Timestamp.class, id)).toInstant()).isEqualTo(due);
    }

    @Test
    void aRestaurantExceptionIsLoggedToo(CapturedOutput output) {
        String c = checkout(owner, CheckoutKind.NEW, null);
        assertThat(create(owner.businessId(), PaymentOutcome.STATE_CHANGED, c, providerEvent(), null)).isTrue();
        assertThat(output.getAll()).contains("event=billing.exception_opened").contains("restaurant=" + owner.businessId()).contains("code=STATE_CHANGED");
    }

    // ---- F-3: the existing actions on a platform exception ----

    private MockHttpServletResponse act(String id, String action, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/billing/exceptions/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", data.adminBearer(admin))).andReturn().getResponse();
    }

    private int version(String id) {
        return jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, id);
    }

    @Test
    void theExistingActionsWorkOnAPlatformExceptionAndEscalationIsNeverAssignedAutomatically() throws Exception {
        String event = providerEvent();
        create(null, "UNATTRIBUTED_PAYMENT", null, event, null);
        String id = idOf("provider_event_id", event, "UNATTRIBUTED_PAYMENT");
        assertThat(act(id, "claim", "{\"rowVersion\":" + version(id) + "}").getStatus()).isEqualTo(200);
        assertThat(act(id, "notes", "{\"rowVersion\":" + version(id) + ",\"note\":\"Looking at Stripe\"}").getStatus()).isEqualTo(200);
        MockHttpServletResponse escalated = act(id, "escalate", "{\"rowVersion\":" + version(id) + ",\"reason\":\"Needs finance\"}");
        assertThat(escalated.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(escalated.getContentAsString()).path("escalatedTo").isNull()).as("no target typed: none assigned").isTrue();
        assertThat(act(id, "resolve", "{\"rowVersion\":" + version(id) + ",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"Refunded in Stripe: re_SyntheticRefund01\"}") /* gate 3 G3-D */.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from billing_exception where id = ?", String.class, id)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForList("select action from admin_audit_event where target_id = ? order by occurred_at", String.class, id))
                .contains("BILLING_EXCEPTION_OPENED", "BILLING_EXCEPTION_CLAIMED", "BILLING_EXCEPTION_NOTE_ADDED", "BILLING_EXCEPTION_ESCALATED", "BILLING_EXCEPTION_RESOLVED");
    }

    // ---- list, summary, history ----

    private JsonNode getJson(String path, String bearer) throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get(path).header("Authorization", bearer)).andReturn().getResponse();
        assertThat(r.getStatus()).as(path).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    @Test
    void theListShowsUnresolvedExceptionsWithTheirRestaurantOrNoneAndPages() throws Exception {
        String c = checkout(owner, CheckoutKind.NEW, null);
        create(owner.businessId(), PaymentOutcome.STATE_CHANGED, c, providerEvent(), null);
        String event = providerEvent();
        create(null, "UNATTRIBUTED_PAYMENT", null, event, null);
        String platform = idOf("provider_event_id", event, "UNATTRIBUTED_PAYMENT");

        JsonNode page = getJson("/api/admin/billing/exceptions", data.adminBearer(admin));
        List<JsonNode> items = new java.util.ArrayList<>();
        page.path("items").forEach(items::add);
        JsonNode p = items.stream().filter(i -> i.path("id").asString().equals(platform)).findFirst().orElseThrow();
        assertThat(p.path("businessId").isNull()).isTrue();
        assertThat(p.path("businessName").isNull()).isTrue();
        assertThat(p.path("code").asString()).isEqualTo("UNATTRIBUTED_PAYMENT");
        assertThat(p.path("dueAt").isMissingNode()).isFalse();
        assertThat(items.stream().anyMatch(i -> owner.businessId().equals(i.path("businessId").asString()) && !i.path("businessName").asString().isBlank())).isTrue();
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(2);

        // a resolved exception leaves the default list, and is found with RESOLVED or ALL
        jdbc.update("update billing_exception set status = 'RESOLVED', resolution = 'NO_ACTION_REQUIRED', resolved_by = 'test', resolved_at = now() where id = ?", platform);
        assertThat(ids(getJson("/api/admin/billing/exceptions?size=100", data.adminBearer(admin)))).doesNotContain(platform);
        assertThat(ids(getJson("/api/admin/billing/exceptions?state=RESOLVED&size=100", data.adminBearer(admin)))).contains(platform);
        assertThat(ids(getJson("/api/admin/billing/exceptions?state=ALL&size=100", data.adminBearer(admin)))).contains(platform);

        JsonNode one = getJson("/api/admin/billing/exceptions?size=1&page=0", data.adminBearer(admin));
        assertThat(one.path("items").size()).isEqualTo(1);
        for (String bad : new String[] {"?size=0", "?size=101", "?page=-1", "?state=NOPE"}) {
            assertThat(mockMvc.perform(get("/api/admin/billing/exceptions" + bad).header("Authorization", data.adminBearer(admin))).andReturn().getResponse().getStatus())
                    .as(bad).isEqualTo(400);
        }
    }

    @Test
    void theSummaryCountsEachUnresolvedExceptionOnceAndKeepsOpenOverdueAndEscalatedSeparately() throws Exception {
        jdbc.update("update billing_exception set status = 'RESOLVED', resolution = 'NO_ACTION_REQUIRED', resolved_by = 'test', resolved_at = now() where status <> 'RESOLVED'");
        String e1 = providerEvent(), e2 = providerEvent(), e3 = providerEvent();
        String e4 = providerEvent();
        create(null, "UNATTRIBUTED_PAYMENT", null, e1, null); // open, overdue
        create(null, "UNATTRIBUTED_PAYMENT", null, e2, null); // open, overdue
        create(null, "UNATTRIBUTED_PAYMENT", null, e3, null); // escalated and past its deadline: not "overdue" (escalated)
        create(null, "UNATTRIBUTED_PAYMENT", null, e4, null); // open, not overdue
        String escalatedId = idOf("provider_event_id", e3, "UNATTRIBUTED_PAYMENT");
        jdbc.update("update billing_exception set due_at = now() - interval '1 hour' where provider_event_id in (?, ?, ?)", e1, e2, e3);
        act(escalatedId, "escalate", "{\"rowVersion\":" + version(escalatedId) + ",\"reason\":\"finance\"}");

        JsonNode s = getJson("/api/admin/billing/exceptions/summary", data.adminBearer(admin));
        assertThat(s.path("unresolved").asInt()).as("distinct records: the badge (never open + overdue = 5)").isEqualTo(4);
        assertThat(s.path("open").asInt()).isEqualTo(3);
        assertThat(s.path("escalated").asInt()).isEqualTo(1);
        assertThat(s.path("autoHandling").asInt()).isZero();
        assertThat(s.path("overdue").asInt()).as("only open-state records past their deadline").isEqualTo(2);
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.path("items").forEach(i -> out.add(i.path("id").asString()));
        return out;
    }

    @Test
    void theHistoryListsTheExceptionsOwnAuditEntriesNewestFirst() throws Exception {
        String otherEvent = providerEvent(); // another exception with its own history, which must not appear
        create(null, "UNATTRIBUTED_PAYMENT", null, otherEvent, null);
        String other = idOf("provider_event_id", otherEvent, "UNATTRIBUTED_PAYMENT");
        act(other, "notes", "{\"rowVersion\":" + version(other) + ",\"note\":\"not this one\"}");
        String event = providerEvent();
        create(null, "UNATTRIBUTED_PAYMENT", null, event, null);
        String id = idOf("provider_event_id", event, "UNATTRIBUTED_PAYMENT");
        act(id, "notes", "{\"rowVersion\":" + version(id) + ",\"note\":\"first look\"}");
        JsonNode h = getJson("/api/admin/billing/exceptions/" + id + "/history", data.adminBearer(admin));
        assertThat(h.size()).isEqualTo(2);
        h.forEach(e -> assertThat(e.path("targetId").asString()).isEqualTo(id));
        assertThat(h.get(0).path("action").asString()).isEqualTo("BILLING_EXCEPTION_NOTE_ADDED");
        assertThat(h.get(0).path("detail").asString()).contains("first look");
        assertThat(h.get(1).path("action").asString()).isEqualTo("BILLING_EXCEPTION_OPENED");
        assertThat(mockMvc.perform(get("/api/admin/billing/exceptions/" + UUID.randomUUID() + "/history").header("Authorization", data.adminBearer(admin)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void onlyTheSuperAdminCanReadExceptions() throws Exception {
        String event = providerEvent();
        create(null, "UNATTRIBUTED_PAYMENT", null, event, null);
        String id = idOf("provider_event_id", event, "UNATTRIBUTED_PAYMENT");
        for (String path : new String[] {"/api/admin/billing/exceptions", "/api/admin/billing/exceptions/summary", "/api/admin/billing/exceptions/" + id + "/history",
                "/api/admin/billing/exceptions/" + id}) {
            assertThat(mockMvc.perform(get(path).header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus()).as(path + " owner").isEqualTo(403);
            assertThat(mockMvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path + " anonymous").isEqualTo(401);
        }
    }
}
