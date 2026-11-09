package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient.Script;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R4-05: the catalogue's C1–C5 and C9, against PostgreSQL with the fake provider. */
class CheckoutCreationTest extends CheckoutTestBase {

    @MockitoSpyBean private JobQueue jobs;
    @Autowired private CheckoutCreateHandler handler;
    @Autowired private PlatformTransactionManager transactions;

    private void recover(String id) {
        handler.handle(new CheckoutCreatePayload(id));
    }

    private String reserveOnly() {
        return checkouts.reserve(owner.businessId(), owner.owner().getId(), price, "crash", Market.ET, registry()).row().id();
    }

    @Autowired private BillingStrategyRegistry registry;

    private com.agafari.com.billing.port.BillingStrategy registry() {
        return registry.forMarket(Market.ET);
    }

    // ---- the normal path

    @Test
    void aCheckoutOpensWithTheProvidersSession() throws Exception {
        perform(owner, "k", price).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.redirectUrl").value("https://fake.test/pay/1")).andExpect(jsonPath("$.checkoutId").isNotEmpty()).andExpect(jsonPath("$.expiresAt").isNotEmpty());
        Map<String, Object> row = only();
        assertThat(row).containsEntry("status", "OPEN").containsEntry("provider", "FAKE_ET").containsEntry("provider_session_ref", "fake_session_1")
                .containsEntry("kind", "NEW").containsEntry("price_id", price).containsEntry("config_version", 1).containsEntry("owner_user_id", owner.owner().getId())
                .containsEntry("business_id", owner.businessId()).containsEntry("idempotency_key", "k");
    }

    @Test
    void theAmountComesFromThePriceAndNotTheClientOrStrategy() throws Exception {
        mockMvc.perform(create("k", price).content("{\"priceId\":\"" + price + "\",\"amount\":1,\"amountMinor\":1,\"currency\":\"USD\",\"businessId\":\"other\"}").header("Authorization", data.bearer(owner))).andExpect(status().isCreated());
        assertThat(only()).containsEntry("expected_amount_minor", ET_AMOUNT).containsEntry("expected_currency", "ETB").containsEntry("business_id", owner.businessId());
    }

    @Test
    void aQuoteLastsTwentyFourHours() throws Exception {
        perform(owner, "k", price);
        Map<String, Object> row = only();
        assertThat(Duration.between(((Timestamp) row.get("created_at")).toInstant(), ((Timestamp) row.get("expires_at")).toInstant())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aBillingAccountIsCreatedFromTheRestaurantsMarketOnce() throws Exception {
        perform(owner, "k", price);
        jdbc.update("update checkout_session set status = 'EXPIRED'");
        perform(owner, "k2", price);
        assertThat(jdbc.queryForMap("select market, provider, count(*) over () as n from billing_account where business_id = ?", owner.businessId()))
                .containsEntry("market", "ET").containsEntry("provider", "FAKE_ET").containsEntry("n", 1L);
    }

    @Test
    void theBackstopJobIsEnqueuedForLaterInTheSameTransaction() throws Exception {
        perform(owner, "k", price);
        Map<String, Object> job = jdbc.queryForMap("select type, key, run_at > now() + interval '90 seconds' as later from job");
        assertThat(job).containsEntry("type", "checkout.create").containsEntry("key", only().get("id")).containsEntry("later", true);
        assertThat(JobFailurePolicy.isFinancial("checkout.create")).isTrue();
    }

    @Test
    void aFailureToEnqueueLeavesNothingAndCallsNoProvider() throws Exception {
        Mockito.doThrow(new IllegalStateException("boom")).when(jobs).enqueue(eq("checkout.create"), any(), any(), any());
        try {
            perform(owner, "k", price).andExpect(status().is5xxServerError());
        } finally {
            Mockito.reset(jobs);
        }
        assertThat(rows()).isEmpty();
        assertThat(fakeEt.sessionsCreated()).isZero();
    }

    // ---- restaurant and market

    @Test
    void anotherOwnersCheckoutIsNotFound() throws Exception {
        perform(owner, "k", price);
        Tenant other = data.tenant("other");
        mockMvc.perform(get("/api/billing/checkouts/{id}", only().get("id")).header("Authorization", data.bearer(other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/billing/checkouts/{id}", only().get("id")).header("Authorization", data.bearer(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void anUnknownCheckoutIsNotFound() throws Exception {
        mockMvc.perform(get("/api/billing/checkouts/{id}", "nope").header("Authorization", data.bearer(owner))).andExpect(status().isNotFound());
    }

    private Tenant american() {
        Tenant t = data.tenant("us-owner");
        jdbc.update("update business set country = 'US' where id = ?", t.businessId());
        return t;
    }

    @Test
    void eachMarketUsesItsOwnProvider() throws Exception {
        Tenant us = american();
        String usPrice = price("MODEL_4", "US", "USD", 900);
        perform(owner, "k", price).andExpect(status().isCreated());
        perform(us, "k", usPrice).andExpect(status().isCreated());
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
        assertThat(fakeUs.sessionsCreated()).isEqualTo(1);
        assertThat(jdbc.queryForMap("select provider, expected_currency from checkout_session where business_id = ?", us.businessId())).containsEntry("provider", "FAKE_US").containsEntry("expected_currency", "USD");
    }

    @Test
    void anotherMarketsPriceIsRefused() throws Exception {
        Tenant us = american();
        perform(us, "k", price).andExpect(status().isBadRequest());
        assertThat(rows()).isEmpty();
        assertThat(fakeEt.sessionsCreated() + fakeUs.sessionsCreated()).isZero();
    }

    // ---- disabled and refused

    @Test
    void withBillingOffTheRouteDoesNotExist() throws Exception {
        checkouts.setBillingEnabled(Market.ET, false);
        checkouts.setBillingEnabled(Market.US, false);
        perform(owner, "k", price).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/billing/checkouts/{id}", "x").header("Authorization", data.bearer(owner))).andExpect(status().isNotFound());
        assertThat(rows()).isEmpty();
        assertThat(fakeEt.sessionsCreated()).isZero();
    }

    @Test
    void billingIsPerMarket() throws Exception {
        checkouts.setBillingEnabled(Market.ET, false);
        perform(owner, "k", price).andExpect(status().isNotFound());
        assertThat(rows()).isEmpty();
    }

    @Test
    void theEndpointNeedsAnOwner() throws Exception {
        assertThat(mockMvc.perform(create("k", price)).andReturn().getResponse().getStatus()).isIn(401, 403);
        mockMvc.perform(create("k", price).header("Authorization", data.adminBearer(admin))).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheOwnerRoleMayCreateEvenWithARestaurant() throws Exception {
        Tenant adminWithRestaurant = data.tenant("admin-owner", com.agafari.com.enums.UserRole.ADMIN);
        perform(adminWithRestaurant, "k", price).andExpect(status().isForbidden());
        assertThat(rows()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {" "})
    void aBlankKeyIsRefused(String key) throws Exception {
        perform(owner, key, price).andExpect(status().isBadRequest());
    }

    @Test
    void aMissingOrOverlongKeyIsRefused() throws Exception {
        perform(owner, null, price).andExpect(status().isBadRequest());
        perform(owner, "x".repeat(256), price).andExpect(status().isBadRequest());
        perform(owner, "x".repeat(255), price).andExpect(status().isCreated());
    }

    @Test
    void aMissingOrUnknownPriceIsRefused() throws Exception {
        perform(owner, "k", "nope").andExpect(status().isBadRequest());
        mockMvc.perform(create("k", price).content("{}").header("Authorization", data.bearer(owner))).andExpect(status().isBadRequest());
    }

    @Test
    void aDisabledPlanIsRefused() throws Exception {
        String disabled = price("MODEL_3", "ET", "ETB", 800);
        perform(owner, "k", disabled).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_ENABLED"));
        assertThat(rows()).isEmpty();
    }

    @Test
    void anInactiveOrFuturePriceIsRefused() throws Exception {
        String future = price("MODEL_4", "ET", "ETB", 800);
        jdbc.execute("alter table plan_price disable trigger plan_price_append_only");
        try {
            jdbc.update("update plan_price set valid_from = now() + interval '1 day' where id = ?", future);
            perform(owner, "k", future).andExpect(status().isConflict());
            jdbc.update("update plan_price set active = false, valid_from = now() - interval '1 day' where id = ?", future);
            perform(owner, "k2", future).andExpect(status().isConflict());
        } finally {
            jdbc.execute("alter table plan_price enable trigger plan_price_append_only");
        }
        assertThat(rows()).isEmpty();
    }

    @Test
    void aPendingPlanRequestIsNeverWithdrawnSilently() throws Exception {
        jdbc.update("insert into plan_request (id, business_id, plan_code, status, config_version, requested_by_user_id, requested_at, row_version) values ('r1', ?, 'MODEL_4', 'PENDING', 1, ?, now(), 0)", owner.businessId(), owner.owner().getId());
        perform(owner, "k", price).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_PENDING"));
        assertThat(rows()).isEmpty();
    }

    // ---- idempotency and resume

    @Test
    void theSameKeyAndRequestReturnsTheSameCheckout() throws Exception {
        perform(owner, "k", price).andExpect(status().isCreated());
        perform(owner, "k", price).andExpect(status().isOk()).andExpect(jsonPath("$.redirectUrl").value("https://fake.test/pay/1"));
        assertThat(rows()).hasSize(1);
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherRequestIsRefused() throws Exception {
        perform(owner, "k", price).andExpect(status().isCreated());
        perform(owner, "k", otherPrice).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(rows()).hasSize(1);
    }

    @Test
    void aKeyStaysBoundAfterTheCheckoutEnded() throws Exception {
        perform(owner, "k", price);
        jdbc.update("update checkout_session set status = 'EXPIRED'");
        perform(owner, "k", price).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.redirectUrl").doesNotExist());
        perform(owner, "k", otherPrice).andExpect(status().isUnprocessableEntity());
        assertThat(rows()).hasSize(1);
    }

    @Test
    void keysArePerRestaurant() throws Exception {
        Tenant other = data.tenant("other");
        perform(owner, "k", price).andExpect(status().isCreated());
        perform(other, "k", otherPrice).andExpect(status().isCreated());
        assertThat(rows()).hasSize(2);
    }

    @Test
    void anotherKeyForTheSamePriceResumes() throws Exception {
        perform(owner, "a", price).andExpect(status().isCreated());
        perform(owner, "b", price).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(rows()).hasSize(1);
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
    }

    @Test
    void anotherPriceWhileOneIsActiveIsRefused() throws Exception {
        perform(owner, "a", price).andExpect(status().isCreated());
        perform(owner, "b", otherPrice).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_ACTIVE"));
        assertThat(rows()).hasSize(1);
    }

    // ---- C1, C2: concurrency

    @Test
    void c1ConcurrentDifferentKeysLeaveOneActiveCheckout() throws Exception {
        var results = race(8, i -> () -> httpStatus(owner, "key-" + i, price));
        assertThat(rows()).hasSize(1);
        assertThat(active()).isEqualTo(1);
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
        assertThat(results).allMatch(s -> s == 201 || s == 200 || s == 202);
        assertThat(results).filteredOn(s -> s == 201).hasSize(1);
    }

    @Test
    void c1ConcurrentDifferentPricesLeaveOneActiveCheckout() throws Exception {
        var results = race(8, i -> () -> httpStatus(owner, "key-" + i, i % 2 == 0 ? price : otherPrice));
        assertThat(active()).isEqualTo(1);
        assertThat(rows()).hasSize(1);
        assertThat(results).allMatch(s -> s == 201 || s == 200 || s == 202 || s == 409);
    }

    @Test
    void c2TheSameKeyConcurrentlyGivesTheSameRow() throws Exception {
        var results = race(8, i -> () -> httpStatus(owner, "same", price));
        assertThat(rows()).hasSize(1);
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
        assertThat(results).filteredOn(s -> s == 201).hasSize(1);
    }

    @Test
    void differentRestaurantsDoNotBlockEachOther() throws Exception {
        Tenant[] tenants = {owner, data.tenant("a"), data.tenant("b"), data.tenant("c")};
        var results = race(4, i -> () -> httpStatus(tenants[i], "k", price));
        assertThat(results).containsOnly(201);
        assertThat(active()).isEqualTo(4);
    }

    // ---- C3: retry during latency

    @Test
    void c3ARetryDuringLatencyMakesNoSecondProviderCall() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1), release = new CountDownLatch(1);
        fakeEt.onCreate(() -> {
            inCall.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var first = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var firstStatus = first.submit(() -> httpStatus(owner, "k", price));
            assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();
            perform(owner, "k", price).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CREATING")).andExpect(jsonPath("$.redirectUrl").doesNotExist());
            release.countDown();
            assertThat(firstStatus.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            release.countDown();
            first.shutdownNow();
        }
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
        assertThat(only()).containsEntry("status", "OPEN");
    }

    @Test
    void aTimeoutAnswersCreatingAndTheJobFinishesWithTheSameSession() throws Exception {
        fakeEt.script(Script.TIMEOUT_AFTER_ACCEPT);
        perform(owner, "k", price).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CREATING"));
        assertThat(only()).containsEntry("status", "CREATING");
        recover((String) only().get("id"));
        assertThat(only()).containsEntry("status", "OPEN").containsEntry("provider_session_ref", "fake_session_1");
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
    }

    // ---- C4, C5: crash recovery

    @Test
    void c4ACrashBeforeTheCallIsRecoveredByTheJob() {
        String id = reserveOnly();
        assertThat(only()).containsEntry("status", "CREATING");
        assertThat(fakeEt.sessionsCreated()).isZero();
        recover(id);
        assertThat(only()).containsEntry("status", "OPEN");
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
    }

    @Test
    void c5ACrashAfterTheCallIsRecoveredWithTheSameKey() {
        String id = reserveOnly();
        fakeEt.createSession(new ProviderSessionRequest(id, new Money(ET_AMOUNT, Currency.ETB), java.time.Instant.now()), "checkout:" + id);
        recover(id);
        assertThat(only()).containsEntry("status", "OPEN").containsEntry("provider_session_ref", "fake_session_1");
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
    }

    private void assertCreateFailed(String reason) {
        Map<String, Object> row = only();
        assertThat(row).containsEntry("status", "CREATE_FAILED").containsEntry("cancelled_reason", reason);
        assertThat(row.get("cancel_requested_at")).isNotNull();
        assertThat(active()).isZero();
    }

    @Test
    void c5WithoutIdempotentCreateTheJobFailsItWithoutCalling() {
        String id = reserveOnly();
        fakeEt.capabilities(new ProviderCapabilities(false, true, true, Duration.ofHours(24), true));
        recover(id);
        assertCreateFailed("NOT_RECOVERABLE");
        assertThat(fakeEt.sessionsCreated()).isZero();
    }

    @Test
    void c5OutsideTheWindowTheJobFailsItWithoutCalling() {
        String id = reserveOnly();
        clock.setOffset(Duration.ofHours(25));
        recover(id);
        assertCreateFailed("NOT_RECOVERABLE");
        assertThat(fakeEt.sessionsCreated()).isZero();
    }

    @Test
    void theJobStillWorksAtTheEdgeOfTheWindow() {
        String id = reserveOnly();
        clock.setOffset(Duration.ofHours(23));
        recover(id);
        assertThat(only()).containsEntry("status", "OPEN");
    }

    @Test
    void aJobTimeoutRetries() {
        String id = reserveOnly();
        fakeEt.script(Script.TIMEOUT_AFTER_ACCEPT);
        assertThatThrownBy(() -> recover(id)).isInstanceOf(TransientJobException.class);
        assertThat(only()).containsEntry("status", "CREATING");
    }

    @Test
    void theJobDoesNothingOnceTheCheckoutLeftCreating() throws Exception {
        perform(owner, "k", price);
        recover((String) only().get("id"));
        assertThat(fakeEt.createCalls()).isEqualTo(1);
    }

    @Test
    void theJobIgnoresAnUnknownCheckout() {
        recover("nope");
        assertThat(fakeEt.sessionsCreated()).isZero();
    }

    @Test
    void theJobCallsNoProviderOnceBillingIsOff() {
        String id = reserveOnly();
        checkouts.setBillingEnabled(Market.ET, false);
        recover(id);
        assertCreateFailed("BILLING_DISABLED");
        assertThat(fakeEt.sessionsCreated()).isZero();
    }

    // ---- C9

    @Test
    void c9APermanentRefusalFailsAndFreesTheSlot() throws Exception {
        fakeEt.script(Script.DECLINE);
        perform(owner, "k", price).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CHECKOUT_CREATE_FAILED"));
        assertCreateFailed("PROVIDER_REFUSED");
        fakeEt.script(Script.SUCCESS);
        perform(owner, "k2", price).andExpect(status().isCreated());
        assertThat(active()).isEqualTo(1);
    }

    @Test
    void c9ARefusalInTheJobFailsAndFreesTheSlot() {
        String id = reserveOnly();
        fakeEt.script(Script.DECLINE);
        recover(id);
        assertCreateFailed("PROVIDER_REFUSED");
    }

    // ---- cancelled while creating

    @Test
    void aCheckoutCancelledDuringTheCallShowsNoUrlAndKeepsTheReference() throws Exception {
        fakeEt.onCreate(() -> jdbc.update("update checkout_session set status = 'CANCEL_REQUESTED', cancel_requested_at = now()"));
        perform(owner, "k", price).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_CANCELLED")).andExpect(jsonPath("$.redirectUrl").doesNotExist());
        assertThat(only()).containsEntry("status", "CANCEL_REQUESTED").containsEntry("provider_session_ref", "fake_session_1").containsEntry("provider_session_url", null);
    }

    // ---- provider-call boundaries

    @Test
    void theRequestPathCallsTheProviderOutsideATransaction() throws Exception {
        perform(owner, "k", price).andExpect(status().isCreated());
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
        assertThat(fakeEt.createRanInTransaction()).isFalse();
    }

    @Test
    void theJobPathCallsTheProviderOutsideATransaction() {
        recover(reserveOnly());
        assertThat(fakeEt.sessionsCreated()).isEqualTo(1);
        assertThat(fakeEt.createRanInTransaction()).isFalse();
    }

    @Test
    void theJobRefusesToRunInsideATransaction() {
        String id = reserveOnly();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> recover(id))).isInstanceOf(ProviderCallInTransactionException.class);
        assertThat(only()).containsEntry("status", "CREATING");
    }

    // ---- NC-04: the return page

    @Test
    void theReturnPageChangesNothing() throws Exception {
        perform(owner, "k", price);
        String id = (String) only().get("id");
        Map<String, Object> before = only();
        mockMvc.perform(get("/api/billing/checkouts/{id}?session=paid&status=success", id).header("Authorization", data.bearer(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(only()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, owner.businessId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment", Integer.class)).isZero();
    }
}
