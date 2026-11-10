package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.notifications.NotificationKind;
import com.agafari.com.notifications.NotificationOutbox;
import com.agafari.com.notifications.NotificationSendHandler;
import com.agafari.com.notifications.NotificationSendPayload;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.MonetizationFixtures;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R4-11: financial exceptions end to end on PostgreSQL: the admin workflow, the owner's "payment under review",
 * the R-2 checkout block and the I1 pause (AC1–AC6). Exceptions are created through the real payment outcome path.
 */
class FinancialExceptionTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Autowired private ExceptionService exceptionService;
    @Autowired private PlanRequestService planRequests;
    @Autowired private NotificationOutbox outbox;
    @Autowired private NotificationSendHandler sender;
    @Autowired private ObjectMapper mapper;

    private User secondAdmin;

    @BeforeEach
    void setUpReview() {
        planRequests.setOwnerPlansVisible(true);
        secondAdmin = data.admin("exception-second");
    }

    @AfterEach
    void resetReview() {
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
    }

    private Instant soon() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    // ---- exceptions created by the outcome path ----

    /** Row 6: a restaurant whose current subscription changed before the payment arrived. */
    private String row6(Tenant t) {
        String c = checkout(t, CheckoutKind.NEW, null);
        MonetizationFixtures.subscription(jdbc, t.businessId(), "MODEL_4", 1, admin.getId());
        process(event(paid(c, soon())));
        return exceptionOf(t, PaymentOutcome.STATE_CHANGED);
    }

    /** Row 0: the verified amount differs; no ledger charge. */
    private String row0(Tenant t, String[] refHolder) {
        String c = checkout(t, CheckoutKind.NEW, null);
        String ref = paid(c, soon(), new Money(ET_AMOUNT - 1, Currency.ETB), metadata(c));
        if (refHolder != null) refHolder[0] = ref;
        process(event(ref));
        return exceptionOf(t, PaymentOutcome.VERIFICATION_FAILED);
    }

    /** Row 3 with a confirmed refund capability: AUTO_HANDLING with a refund request. */
    private String autoHandling(Tenant t) {
        Instant expires = micros(Instant.now().plus(1, ChronoUnit.HOURS));
        process(event(paid(checkout(t, CheckoutKind.NEW, null, price, ET_AMOUNT, expires), expires.plusSeconds(1))));
        return exceptionOf(t, PaymentOutcome.LATE_PAYMENT);
    }

    private String exceptionOf(Tenant t, String code) {
        return jdbc.queryForObject("select id from billing_exception where business_id = ? and code = ?", String.class, t.businessId(), code);
    }

    private Map<String, Object> exceptionRow(String id) {
        return jdbc.queryForMap("select * from billing_exception where id = ?", id);
    }

    private int rowVersion(String id) {
        return jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, id);
    }

    // ---- admin HTTP ----

    private MockHttpServletResponse act(User who, String id, String action, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/billing/exceptions/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", data.adminBearer(who))).andReturn().getResponse();
    }

    private MockHttpServletResponse act(String id, String action, String body) throws Exception {
        return act(admin, id, action, body);
    }

    private String actionBody(String id, String extra) {
        return "{\"rowVersion\":" + rowVersion(id) + (extra == null ? "" : "," + extra) + "}";
    }

    private static String code(MockHttpServletResponse r) throws Exception {
        return r.getContentAsString();
    }

    // ---- AC1 ----

    @Test
    void reDeliveryOfThePaymentLeavesOneException() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        MonetizationFixtures.subscription(jdbc, owner.businessId(), "MODEL_4", 1, admin.getId());
        String ref = paid(c, soon());
        process(event(ref));
        process(event(ref));

        assertThat(count("select count(*) from billing_exception where business_id = ?", owner.businessId())).isEqualTo(1);
    }

    @Test
    void creatingTheSameExceptionTwiceForOnePaymentIsANoOp() {
        String id = row6(owner);
        Map<String, Object> row = exceptionRow(id);

        boolean again = new TransactionTemplate(transactions).execute(s -> exceptionService.create(owner.businessId(), PaymentOutcome.STATE_CHANGED, (String) row.get("payment_id"),
                (String) row.get("checkout_id"), (String) row.get("provider_event_id"), null, ET_AMOUNT, "ETB", Instant.now(), true, "OPEN", Map.of("source", "reconciliation"), Instant.now()));

        assertThat(again).isFalse();
        assertThat(count("select count(*) from billing_exception where business_id = ?", owner.businessId())).isEqualTo(1);
        assertThat(exceptionRow(id).get("facts").toString()).as("facts are never overwritten").doesNotContain("reconciliation");
    }

    // ---- AC2: R-2 block, and what isn't blocked ----

    @Test
    void whileAFundsExceptionIsOpenPaidCheckoutsAreRefused() throws Exception {
        row6(owner);

        MockHttpServletResponse r = perform(owner, "k-review-" + KEYS.incrementAndGet(), price).andReturn().getResponse();

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).contains("PAYMENT_UNDER_REVIEW");
        assertThat(count("select count(*) from checkout_session where business_id = ?", owner.businessId())).as("only the row-6 fixture's own checkout").isEqualTo(1);
        assertThat(fakeEt.createCalls()).as("the provider is never asked").isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO_HANDLING", "OPEN", "IN_REVIEW", "ESCALATED"})
    void everyOpenReviewStatusBlocksPaidCheckouts(String status) throws Exception {
        seedException(owner, status, true);

        assertThat(httpStatusWithConflictCode(owner, "k-status-" + KEYS.incrementAndGet(), price, "PAYMENT_UNDER_REVIEW")).isEqualTo(409);
    }

    @Test
    void aResolvedReviewOrOneWithoutCustomerFundsDoesNotBlock() throws Exception {
        seedException(owner, "RESOLVED", true);
        seedException(owner, "OPEN", false);

        assertThat(httpStatus(owner, "k-free-" + KEYS.incrementAndGet(), price)).isEqualTo(201);
    }

    @Test
    void anotherRestaurantsReviewDoesNotBlockThisOne() throws Exception {
        row6(data.tenant("exception-neighbour"));

        assertThat(httpStatus(owner, "k-neighbour-" + KEYS.incrementAndGet(), price)).isEqualTo(201);
    }

    @Test
    void freeWithAdsSelfServiceStillWorksDuringAReview() throws Exception {
        Tenant t = data.tenant("exception-free");
        String c = checkout(t, CheckoutKind.NEW, null);
        process(event(paid(c, soon(), new Money(ET_AMOUNT - 1, Currency.ETB), metadata(c)))); // row 0: under review, no subscription
        planRequests.setSelfServiceEnabled(true);
        jdbc.update("update users set email_verified_at = now() where id = ?", t.owner().getId());

        int status = mockMvc.perform(post("/api/plan/activations").contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}")
                .header("Authorization", data.bearer(t))).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(201);
        assertThat(jdbc.queryForObject("select source from business_subscription where business_id = ? and status = 'ACTIVE'", String.class, t.businessId())).isEqualTo("OWNER");
    }

    @Test
    void anExistingPaidSubscriptionAndItsPlanViewAreUntouchedByAReview() throws Exception {
        String c = checkout(owner, CheckoutKind.NEW, null);
        process(event(paid(c, soon())));
        String sub = current(owner);
        String second = paid(c, soon().plusSeconds(1)); // row 2: a duplicate payment, under review
        process(event(second));

        JsonNode view = planView(owner);

        assertThat(current(owner)).isEqualTo(sub);
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, sub)).isEqualTo("ACTIVE");
        assertThat(view.path("current").path("planCode").asString()).isEqualTo("MODEL_4");
        assertThat(view.path("paymentUnderReview").isMissingNode()).isFalse();
    }

    @Test
    void onlyCheckoutsTheOwnerViewAndPaymentRequestNoticesConsultTheReview() throws Exception {
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            List<String> users = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String code = java.nio.file.Files.readString(p);
                    return code.contains("PaymentReview paymentReview") || code.contains("PaymentReview review") || code.contains("PaymentReviewCheck review");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(users).as("publishing, entitlements and self-service never consult it")
                    .containsExactlyInAnyOrder("CheckoutService.java", "PlanRequestService.java", "NotificationOutbox.java", "NotificationSendHandler.java");
        }
    }

    // ---- owner view ----

    @Test
    void theOwnerSeesTheirPaymentUnderReviewUntilItIsResolved() throws Exception {
        String id = row6(owner);
        Map<String, Object> row = exceptionRow(id);

        JsonNode review = planView(owner).path("paymentUnderReview");
        assertThat(review.path("amountMinor").asLong()).isEqualTo(ET_AMOUNT);
        assertThat(review.path("currency").asString()).isEqualTo("ETB");
        assertThat(Instant.parse(review.path("paidAt").asString())).isEqualTo(((Timestamp) row.get("paid_at")).toInstant());
        assertThat(review.path("reference").asString()).isEqualTo(id);
        assertThat(review.path("since").isMissingNode()).isFalse();

        assertThat(act(id, "resolve", actionBody(id, "\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"Refunded by bank transfer\"")).getStatus()).isEqualTo(200);

        assertThat(planView(owner).path("paymentUnderReview").isMissingNode()).isTrue();
        assertThat(httpStatus(owner, "k-after-" + KEYS.incrementAndGet(), price)).as("checkouts open again").isNotEqualTo(409);
    }

    @Test
    void anOwnerNeverSeesAnotherRestaurantsReview() throws Exception {
        row6(data.tenant("exception-other-owner"));

        assertThat(planView(owner).path("paymentUnderReview").isMissingNode()).isTrue();
    }

    private JsonNode planView(Tenant t) throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    // ---- AC3: I1 ----

    @Test
    void paymentRequestNoticesArePausedDuringAReviewAndEssentialNoticesContinue() {
        String id = row6(owner);

        boolean request = notice(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST, "renewal-1");
        boolean expiry = notice(owner, NotificationKind.EXPIRY_NOTICE, "expiry-1");
        boolean review = notice(owner, NotificationKind.REVIEW_STATUS, "review-1");

        assertThat(request).as("paused").isFalse();
        assertThat(count("select count(*) from notification where business_id = ? and kind = 'RENEWAL_PAYMENT_REQUEST'", owner.businessId())).isZero();
        assertThat(expiry).isTrue();
        assertThat(review).isTrue();
        deliverAll(owner);
        assertThat(count("select count(*) from notification where business_id = ? and status = 'SENT'", owner.businessId())).isEqualTo(2);

        resolve(id);
        assertThat(notice(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST, "renewal-2")).as("resumes after the review").isTrue();
        deliverAll(owner);
        assertThat(jdbc.queryForObject("select status from notification where business_id = ? and key = 'renewal-2'", String.class, owner.businessId())).isEqualTo("SENT");
    }

    @Test
    void aPaymentRequestQueuedBeforeTheReviewIsNotDeliveredDuringIt() {
        assertThat(notice(owner, NotificationKind.RENEWAL_PAYMENT_REQUEST, "renewal-early")).isTrue();
        row6(owner);

        deliverAll(owner);

        assertThat(jdbc.queryForMap("select status, attempts from notification where business_id = ? and key = 'renewal-early'", owner.businessId()))
                .containsEntry("status", "FAILED").containsEntry("attempts", 0);
    }

    private boolean notice(Tenant t, NotificationKind kind, String key) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(s -> outbox.add(t.businessId(), kind, key, Map.of("n", key))));
    }

    private void deliverAll(Tenant t) {
        for (String id : jdbc.queryForList("select id from notification where business_id = ? and status = 'PENDING'", String.class, t.businessId())) {
            sender.handle(new NotificationSendPayload(id, id));
        }
    }

    // ---- workflow ----

    @Test
    void anExceptionIsClaimedNotedEscalatedAndResolvedWithEveryStepAudited() throws Exception {
        String id = row6(owner);

        assertThat(act(id, "claim", actionBody(id, null)).getStatus()).isEqualTo(200);
        assertThat(exceptionRow(id)).containsEntry("status", "IN_REVIEW").containsEntry("owner_user_id", admin.getId());
        assertThat(exceptionRow(id).get("claimed_at")).isNotNull();

        assertThat(act(id, "notes", actionBody(id, "\"note\":\"Called the bank\"")).getStatus()).isEqualTo(200);
        assertThat(act(id, "escalate", actionBody(id, "\"reason\":\"Needs finance\",\"escalatedTo\":\"finance\"")).getStatus()).isEqualTo(200);
        assertThat(exceptionRow(id)).containsEntry("status", "ESCALATED").containsEntry("escalation_reason", "Needs finance").containsEntry("escalated_to", "finance");

        assertThat(act(id, "resolve", actionBody(id, "\"resolution\":\"NO_ACTION_REQUIRED\",\"note\":\"Duplicate of an offline record\"")).getStatus()).isEqualTo(200);
        assertThat(exceptionRow(id)).containsEntry("status", "RESOLVED").containsEntry("resolution", "NO_ACTION_REQUIRED")
                .containsEntry("resolution_note", "Duplicate of an offline record").containsEntry("resolved_by", admin.getId());

        List<Map<String, Object>> audit = jdbc.queryForList("select action, actor_user_id, occurred_at, detail from admin_audit_event where target_id = ? and action like 'BILLING_EXCEPTION_%' order by occurred_at", id);
        assertThat(audit).extracting(a -> a.get("action")).containsSubsequence("BILLING_EXCEPTION_OPENED", "BILLING_EXCEPTION_CLAIMED", "BILLING_EXCEPTION_NOTE_ADDED",
                "BILLING_EXCEPTION_ESCALATED", "BILLING_EXCEPTION_RESOLVED");
        assertThat(audit.stream().filter(a -> !"BILLING_EXCEPTION_OPENED".equals(a.get("action"))))
                .allMatch(a -> admin.getId().equals(a.get("actor_user_id")) && a.get("occurred_at") != null);
        assertThat(audit.stream().filter(a -> "BILLING_EXCEPTION_NOTE_ADDED".equals(a.get("action")))).singleElement()
                .satisfies(a -> assertThat((String) a.get("detail")).contains("Called the bank"));
    }

    @Test
    void aStaleRowVersionIsRefusedAndChangesNothing() throws Exception {
        String id = row6(owner);
        MockHttpServletResponse r = act(id, "claim", "{\"rowVersion\":" + (rowVersion(id) + 5) + "}");

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).contains("BILLING_EXCEPTION_VERSION_CONFLICT");
        assertThat(exceptionRow(id)).containsEntry("status", "OPEN");
        assertThat(act(id, "claim", "{}").getStatus()).as("a row version is required").isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-resolution", "unapproved-resolution", "missing-note", "refunded-automatically"})
    void aResolutionNeedsAnApprovedOutcomeAndANote(String variant) throws Exception {
        String id = row6(owner);
        String extra = switch (variant) {
            case "missing-resolution" -> "\"note\":\"x\"";
            case "unapproved-resolution" -> "\"resolution\":\"ACTIVATED_UNDER_POLICY\",\"note\":\"x\"";
            case "missing-note" -> "\"resolution\":\"NO_ACTION_REQUIRED\"";
            default -> "\"resolution\":\"REFUNDED_AUTOMATICALLY\",\"note\":\"x\"";
        };

        assertThat(act(id, "resolve", actionBody(id, extra)).getStatus()).isEqualTo(400);
        assertThat(exceptionRow(id)).containsEntry("status", "OPEN");
    }

    @Test
    void anEscalationNeedsAReason() throws Exception {
        String id = row6(owner);

        assertThat(act(id, "escalate", actionBody(id, null)).getStatus()).isEqualTo(400);
        assertThat(act(id, "notes", actionBody(id, "\"note\":\"   \"")).getStatus()).as("a blank note").isEqualTo(400);
    }

    @Test
    void aResolvedExceptionAcceptsNoFurtherAction() throws Exception {
        String id = row6(owner);
        resolve(id);

        for (String action : List.of("claim", "escalate", "resolve", "notes")) {
            MockHttpServletResponse r = act(id, action, actionBody(id, "\"reason\":\"r\",\"note\":\"n\",\"resolution\":\"NO_ACTION_REQUIRED\""));
            assertThat(r.getStatus()).as(action).isEqualTo(409);
            assertThat(code(r)).contains("BILLING_EXCEPTION_ACTION_NOT_ALLOWED");
        }
    }

    @Test
    void anAutomaticRefundInProgressCanOnlyBeNoted() throws Exception {
        String id = autoHandling(owner);

        for (String action : List.of("claim", "escalate", "resolve")) {
            assertThat(act(id, action, actionBody(id, "\"reason\":\"r\",\"note\":\"n\",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\"")).getStatus()).as(action).isEqualTo(409);
        }
        assertThat(act(id, "notes", actionBody(id, "\"note\":\"Watching the refund\"")).getStatus()).isEqualTo(200);
        assertThat(exceptionRow(id)).containsEntry("status", "AUTO_HANDLING");
        assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, exceptionRow(id).get("refund_request_id"))).isEqualTo("REQUESTED");
    }

    @Test
    void aClaimedExceptionCantBeClaimedAgain() throws Exception {
        String id = row6(owner);
        act(id, "claim", actionBody(id, null));

        MockHttpServletResponse r = act(secondAdmin, id, "claim", actionBody(id, null));

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(exceptionRow(id)).containsEntry("owner_user_id", admin.getId());
    }

    @Test
    void twoAdminsClaimingAtOnceLeaveOneOwner() throws Exception {
        for (int round = 0; round < 6; round++) {
            String id = row6(data.tenant("exception-claim-race-" + round));
            String b = actionBody(id, null);
            List<Integer> statuses = race(2, i -> () -> act(i == 0 ? admin : secondAdmin, id, "claim", b).getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(exceptionRow(id).get("owner_user_id")).isIn(admin.getId(), secondAdmin.getId());
            assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_CLAIMED'", id)).isEqualTo(1);
        }
    }

    @Test
    void anOwnerTokenIsRefusedOnEveryAdminAction() throws Exception {
        String id = row6(owner);
        for (String action : List.of("claim", "notes", "escalate", "resolve", "replay")) {
            int status = mockMvc.perform(post("/api/admin/billing/exceptions/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON).content(actionBody(id, null))
                    .header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus();
            assertThat(status).as(action).isEqualTo(403);
        }
        assertThat(exceptionRow(id)).containsEntry("status", "OPEN");
        assertThat(mockMvc.perform(get("/api/admin/billing/exceptions/" + id).header("Authorization", data.adminBearer(admin))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ---- AC4 replay; AC6 row 6 ----

    @Test
    void aReplayGoesThroughTheNormalPathWithFullValidation() throws Exception {
        String[] ref = new String[1];
        String id = row0(owner, ref);
        Map<String, String> meta = metadata((String) exceptionRow(id).get("checkout_id"));
        fakeEt.payment(new VerifiedPayment(ref[0], ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), soon(), meta)); // the provider now reports the right amount
        int fetches = fakeEt.verifyCalls();

        MockHttpServletResponse r = act(id, "replay", actionBody(id, null));

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(code(r)).contains("PROCESSED");
        assertThat(fakeEt.verifyCalls()).as("re-fetched from the provider").isEqualTo(fetches + 1);
        assertThat(providerSubscriptions(owner)).as("activated through the normal path").isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
        assertThat(exceptionRow(id)).as("the review stays for the admin to close").containsEntry("status", "OPEN");
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'BILLING_EXCEPTION_REPLAYED'", id)).isEqualTo(1);

        assertThat(act(id, "replay", actionBody(id, null)).getStatus()).as("idempotent").isEqualTo(200);
        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void aReplayStillRefusesEvidenceThatIsStillWrong() throws Exception {
        String id = row0(owner, null);

        assertThat(act(id, "replay", actionBody(id, null)).getStatus()).isEqualTo(200);

        assertThat(count("select count(*) from payment where business_id = ?", owner.businessId())).isZero();
        assertThat(current(owner)).isNull();
        assertThat(count("select count(*) from billing_exception where business_id = ?", owner.businessId())).as("no second exception").isEqualTo(1);
    }

    @Test
    void noActionOnARow6ExceptionActivatesOrRefunds() throws Exception {
        String id = row6(owner);
        String manual = current(owner);
        Map<String, Object> payment = jdbc.queryForMap("select * from payment where id = ?", exceptionRow(id).get("payment_id"));

        act(id, "claim", actionBody(id, null));
        act(id, "notes", actionBody(id, "\"note\":\"n\""));
        act(id, "replay", actionBody(id, null));
        act(id, "escalate", actionBody(id, "\"reason\":\"r\""));
        act(id, "replay", actionBody(id, null));
        act(id, "resolve", actionBody(id, "\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"n\""));

        assertThat(current(owner)).isEqualTo(manual);
        assertThat(providerSubscriptions(owner)).isZero();
        assertThat(periods(owner)).isZero();
        assertThat(refunds(owner)).isZero();
        assertThat(fakeEt.refundsCreated()).isZero();
        assertThat(jdbc.queryForMap("select * from payment where id = ?", payment.get("id"))).as("the ledger is never changed").isEqualTo(payment);
    }

    @Test
    void aReplayWhileBillingIsInactiveIsRefusedAndChangesNothing() throws Exception {
        String id = row0(owner, null);
        outcomes.setBillingEnabled(Market.ET, false);

        MockHttpServletResponse r = act(id, "replay", actionBody(id, null));

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).contains("BILLING_EXCEPTION_NOT_REPLAYABLE");
        assertThat(current(owner)).isNull();
    }

    @Test
    void anExceptionAboutARefundCapabilityGapCanBeResolvedWithoutAnyRefund() throws Exception {
        fakeEt.capabilities(ProviderCapabilities.none());
        Instant expires = micros(Instant.now().plus(1, ChronoUnit.HOURS));
        process(event(paid(checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT, expires), expires.plusSeconds(1))));
        String id = exceptionOf(owner, PaymentOutcome.REFUND_REQUIRED_MANUAL);

        assertThat(act(id, "resolve", actionBody(id, "\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"Refunded at the bank\"")).getStatus()).isEqualTo(200);

        assertThat(refunds(owner)).isZero();
        assertThat(exceptionRow(id)).containsEntry("status", "RESOLVED");
    }

    // ---- helpers ----

    private void resolve(String id) {
        try {
            assertThat(act(id, "resolve", actionBody(id, "\"resolution\":\"NO_ACTION_REQUIRED\",\"note\":\"done\"")).getStatus()).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedException(Tenant t, String status, boolean funds) {
        String id = java.util.UUID.randomUUID().toString();
        boolean resolved = status.equals("RESOLVED");
        jdbc.update("insert into billing_exception (id, business_id, code, involves_customer_funds, facts, status, resolution, resolved_by, resolved_at, escalated_at, escalation_reason, created_at) "
                        + "values (?, ?, 'SEEDED', ?, '{}'::jsonb, ?, ?, ?, ?, ?, ?, now())",
                id, t.businessId(), funds, status, resolved ? "NO_ACTION_REQUIRED" : null, resolved ? admin.getId() : null, resolved ? Timestamp.from(Instant.now()) : null,
                status.equals("ESCALATED") ? Timestamp.from(Instant.now()) : null, status.equals("ESCALATED") ? "seeded" : null);
    }
}
