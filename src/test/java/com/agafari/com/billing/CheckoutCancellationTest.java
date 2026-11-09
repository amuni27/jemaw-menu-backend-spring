package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient.Script;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.dto.request.MonetizationConfigRequest;
import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationConfigService;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R4-06: durable cancellation, replacement and configuration-change cancellation, against PostgreSQL with the fake. */
class CheckoutCancellationTest extends CheckoutTestBase {

    @MockitoSpyBean private JobQueue jobs;
    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private MonetizationConfigService config;
    @Autowired private CheckoutExpireHandler expireHandler;
    @Autowired private CheckoutCreateHandler createHandler;
    @Autowired private BillingStrategyRegistry registry;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private CheckoutCancellation cancellation;
    @Autowired private javax.sql.DataSource dataSource;

    private String open() throws Exception {
        perform(owner, "k", price).andExpect(status().isCreated());
        return (String) only().get("id");
    }

    private ResultActions cancel(Tenant t, String id) throws Exception {
        return mockMvc.perform(post("/api/billing/checkouts/{id}/cancel", id).header("Authorization", data.bearer(t)));
    }

    private ResultActions replace(String key, String priceId, String id, Object version) throws Exception {
        String body = "{\"priceId\":\"" + priceId + "\",\"replaceCheckoutId\":" + (id == null ? "null" : "\"" + id + "\"") + ",\"replaceRowVersion\":" + version + "}";
        return mockMvc.perform(create(key, priceId).content(body).contentType(MediaType.APPLICATION_JSON).header("Authorization", data.bearer(owner)));
    }

    private int expireJobs(String id) {
        return jdbc.queryForObject("select count(*) from job where type = 'checkout.expire' and key = ?", Integer.class, id);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from checkout_session where id = ?", id);
    }

    private void change(String setting, List<String> models, int expected) {
        config.change(new MonetizationConfigRequest(setting, models, expected), admin.getId(), "admin@example.test");
    }

    private void expire(String id) {
        expireHandler.handle(new CheckoutExpirePayload(id));
    }

    private void assertCancelRequested(String id, String reason) {
        Map<String, Object> r = row(id);
        assertThat(r).containsEntry("status", "CANCEL_REQUESTED").containsEntry("cancelled_reason", reason);
        assertThat(r.get("cancel_requested_at")).isNotNull();
    }

    private String creating() {
        return checkouts.reserve(owner.businessId(), owner.owner().getId(), price, "creating", Market.ET, registry.forMarket(Market.ET)).row().id();
    }

    // ---- owner cancel

    @Test
    void anOwnerCancelsAnOpenCheckout() throws Exception {
        String id = open();
        int version = (Integer) row(id).get("row_version");
        cancel(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED")).andExpect(jsonPath("$.redirectUrl").doesNotExist());
        assertCancelRequested(id, "OWNER_CANCELLED");
        assertThat(row(id).get("row_version")).isEqualTo(version + 1);
        assertThat(expireJobs(id)).isEqualTo(1);
        assertThat(fakeEt.expireCalls()).isZero();
    }

    @Test
    void cancellingTwiceChangesNothingMore() throws Exception {
        String id = open();
        cancel(owner, id).andExpect(status().isOk());
        Map<String, Object> first = row(id);
        cancel(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
        assertThat(row(id)).isEqualTo(first);
        assertThat(expireJobs(id)).isEqualTo(1);
    }

    @Test
    void anotherOwnersCheckoutCantBeCancelled() throws Exception {
        String id = open();
        cancel(data.tenant("other"), id).andExpect(status().isNotFound());
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void aFinishedCheckoutCantBeCancelled() throws Exception {
        String id = open();
        jdbc.update("update checkout_session set status = 'EXPIRED'");
        cancel(owner, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_NOT_CANCELLABLE"));
        assertThat(row(id)).containsEntry("status", "EXPIRED");
    }

    @Test
    void withBillingOffCancelDoesNotExist() throws Exception {
        String id = open();
        checkouts.setBillingEnabled(Market.ET, false);
        checkouts.setBillingEnabled(Market.US, false);
        cancel(owner, id).andExpect(status().isNotFound());
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void aCancelledCheckoutFreesTheSlot() throws Exception {
        String id = open();
        cancel(owner, id);
        perform(owner, "k2", otherPrice).andExpect(status().isCreated());
        assertThat(active()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "CANCEL_REQUESTED");
    }

    @Test
    void cancellingRecordsTheRequestTimeForTheMatrix() throws Exception {
        String id = open();
        clock.setOffset(Duration.ofHours(3));
        var expected = clock.now();
        cancel(owner, id);
        assertThat(Duration.between(expected, ((Timestamp) row(id).get("cancel_requested_at")).toInstant()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    // ---- AC2: cancel while CREATING

    @Test
    void cancelWhileCreatingNeverReturnsAUrl() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1), release = new CountDownLatch(1);
        fakeEt.onCreate(() -> {
            inCall.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var creating = pool.submit(() -> perform(owner, "k", price).andReturn().getResponse());
            assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();
            String id = (String) only().get("id");
            cancel(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED")).andExpect(jsonPath("$.redirectUrl").doesNotExist());
            assertThat(expireJobs(id)).isZero();
            release.countDown();
            var response = creating.get(20, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentAsString()).contains("CHECKOUT_CANCELLED").doesNotContain("fake.test");
            assertThat(row(id)).containsEntry("provider_session_ref", "fake_session_1").containsEntry("provider_session_url", null).containsEntry("status", "CANCEL_REQUESTED");
            assertThat(expireJobs(id)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aCancelledCreatingRowIsNotOpenedByTheJob() throws Exception {
        String id = creating();
        cancel(owner, id);
        createHandler.handle(new CheckoutCreatePayload(id));
        assertThat(fakeEt.createCalls()).isZero();
        assertThat(row(id)).containsEntry("status", "CANCEL_REQUESTED");
    }

    @Test
    void aCancelledCreatingRowWithNoReferenceHasNothingToExpire() throws Exception {
        String id = creating();
        cancel(owner, id);
        expire(id);
        assertThat(fakeEt.expireCalls()).isZero();
    }

    // ---- replacement

    private int version(String id) {
        return (Integer) row(id).get("row_version");
    }

    @Test
    void aConfirmedReplacementCancelsTheOldAndCreatesTheNew() throws Exception {
        String old = open();
        replace("k2", otherPrice, old, version(old)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
        assertCancelRequested(old, "OWNER_REPLACED");
        assertThat(expireJobs(old)).isEqualTo(1);
        assertThat(rows()).hasSize(2);
        assertThat(active()).isEqualTo(1);
    }

    @Test
    void anUnconfirmedReplacementIsRefused() throws Exception {
        String old = open();
        perform(owner, "k2", otherPrice).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_ACTIVE"));
        assertThat(row(old)).containsEntry("status", "OPEN");
    }

    @Test
    void aStaleVersionIsRefused() throws Exception {
        String old = open();
        replace("k2", otherPrice, old, version(old) + 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_VERSION_CONFLICT"));
        assertThat(row(old)).containsEntry("status", "OPEN");
        assertThat(rows()).hasSize(1);
    }

    @Test
    void aConfirmationNamingAnotherCheckoutIsRefused() throws Exception {
        String old = open();
        replace("k2", otherPrice, "someone-else", version(old)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_VERSION_CONFLICT"));
        assertThat(row(old)).containsEntry("status", "OPEN");
    }

    @Test
    void aConfirmationWithoutAVersionIsRefused() throws Exception {
        String old = open();
        replace("k2", otherPrice, old, "null").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_VERSION_CONFLICT"));
    }

    @Test
    void replayingAReplacementReturnsTheNewCheckout() throws Exception {
        String old = open();
        int v = version(old);
        replace("k2", otherPrice, old, v).andExpect(status().isCreated());
        replace("k2", otherPrice, old, v).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(rows()).hasSize(2);
        assertThat(fakeEt.sessionsCreated()).isEqualTo(2);
    }

    @Test
    void aFailedReservationLeavesTheOldCheckoutUntouched() throws Exception {
        String old = open();
        Mockito.doThrow(new IllegalStateException("boom")).when(jobs).enqueue(eq("checkout.create"), any(), any(), any());
        try {
            replace("k2", otherPrice, old, version(old)).andExpect(status().is5xxServerError());
        } finally {
            Mockito.reset(jobs);
        }
        assertThat(row(old)).containsEntry("status", "OPEN").containsEntry("cancel_requested_at", null);
        assertThat(expireJobs(old)).isZero();
        assertThat(rows()).hasSize(1);
    }

    @Test
    void replacingACreatingCheckoutDefersItsExpiry() throws Exception {
        String old = creating();
        replace("k2", otherPrice, old, version(old)).andExpect(status().isCreated());
        assertCancelRequested(old, "OWNER_REPLACED");
        assertThat(expireJobs(old)).isZero();
    }

    @Test
    void concurrentReplacementsLeaveOneActiveCheckout() throws Exception {
        String old = open();
        int v = version(old);
        String third = price("MODEL_2", "ET", "ETB", 800);
        var results = race(6, i -> () -> replace("r" + i, i % 2 == 0 ? otherPrice : third, old, v).andReturn().getResponse().getStatus());
        assertThat(active()).isEqualTo(1);
        assertThat(results).doesNotContain(500);
        assertThat(results).filteredOn(s -> s == 201).hasSize(1);
        assertThat(expireJobs(old)).isEqualTo(1);
    }

    // ---- AC1: configuration changes

    @Test
    void disablingAPlanCancelsItsCheckouts() throws Exception {
        String id = open();
        change("SETTING_2", List.of("MODEL_2"), 1);
        assertCancelRequested(id, "PLAN_DISABLED");
        assertThat(expireJobs(id)).isEqualTo(1);
    }

    @Test
    void aSettingChangeCancelsWithItsOwnReason() throws Exception {
        String id = open();
        change("SETTING_1", List.of("MODEL_1"), 1);
        assertCancelRequested(id, "CONFIG_CHANGED");
    }

    @Test
    void aCheckoutOfAPlanStillEnabledIsUntouched() throws Exception {
        String id = open();
        change("SETTING_2", List.of("MODEL_2", "MODEL_3", "MODEL_4"), 1);
        assertThat(row(id)).containsEntry("status", "OPEN");
        assertThat(expireJobs(id)).isZero();
    }

    @Test
    void anUnchangedConfigurationDoesNothing() throws Exception {
        String id = open();
        change("SETTING_2", List.of("MODEL_2", "MODEL_4"), 1);
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void disablingAPlanCoversCreatingCheckoutsAndDefersTheirExpiry() {
        String id = creating();
        change("SETTING_2", List.of("MODEL_2"), 1);
        assertCancelRequested(id, "PLAN_DISABLED");
        assertThat(expireJobs(id)).isZero();
    }

    @Test
    void finishedCheckoutsAreLeftAlone() throws Exception {
        String id = open();
        jdbc.update("update checkout_session set status = 'EXPIRED'");
        change("SETTING_2", List.of("MODEL_2"), 1);
        assertThat(row(id)).containsEntry("status", "EXPIRED").containsEntry("cancel_requested_at", null);
    }

    @Test
    void allRestaurantsAreCovered() throws Exception {
        String a = open();
        Tenant other = data.tenant("other");
        perform(other, "k", price).andExpect(status().isCreated());
        change("SETTING_2", List.of("MODEL_2"), 1);
        assertThat(jdbc.queryForObject("select count(*) from checkout_session where status = 'CANCEL_REQUESTED' and cancelled_reason = 'PLAN_DISABLED'", Integer.class)).isEqualTo(2);
        assertThat(expireJobs(a)).isEqualTo(1);
    }

    @Test
    void aFailedCancellationRollsBackThePlanDisable() throws Exception {
        String id = open();
        Mockito.doThrow(new IllegalStateException("boom")).when(jobs).enqueue(eq("checkout.expire"), anyString(), any());
        try {
            assertThatThrownBy(() -> change("SETTING_2", List.of("MODEL_2"), 1)).hasMessageContaining("boom");
        } finally {
            Mockito.reset(jobs);
        }
        assertThat(row(id)).containsEntry("status", "OPEN");
        assertThat(jdbc.queryForObject("select max(version) from monetization_config", Integer.class)).isEqualTo(1);
    }

    @Test
    void aFailedPlanDisableRollsBackTheCancellation() throws Exception {
        String id = open();
        Mockito.doThrow(new IllegalStateException("boom")).when(audit).success(eq(MonetizationConfigService.CHANGED), any(), any(), any(), any(), any());
        try {
            assertThatThrownBy(() -> change("SETTING_2", List.of("MODEL_2"), 1)).hasMessageContaining("boom");
        } finally {
            Mockito.reset(audit);
        }
        assertThat(row(id)).containsEntry("status", "OPEN");
        assertThat(expireJobs(id)).isZero();
        assertThat(jdbc.queryForObject("select max(version) from monetization_config", Integer.class)).isEqualTo(1);
    }

    @Test
    void aCheckoutOpeningDuringTheChangeStillGetsItsExpiry() throws Exception {
        String id = creating();
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var update = c.prepareStatement("update checkout_session set status = 'OPEN', provider_session_ref = 'late-ref', provider_session_url = 'u' where id = ?")) {
                update.setString(1, id);
                update.executeUpdate();
            }
            var pool = Executors.newSingleThreadExecutor();
            try {
                var change = pool.submit(() -> change("SETTING_2", List.of("MODEL_2"), 1));
                for (int i = 0; i < 100 && jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like '%checkout_session%'", Integer.class) == 0; i++) Thread.sleep(50);
                c.commit();
                change.get(20, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertCancelRequested(id, "PLAN_DISABLED");
        assertThat(expireJobs(id)).isEqualTo(1);
    }

    @Test
    void aRequestChangesOnlyAnActiveCheckout() throws Exception {
        String id = open();
        jdbc.update("update checkout_session set status = 'EXPIRED'");
        new TransactionTemplate(transactions).executeWithoutResult(s -> cancellation.request(id, "ref", CheckoutCancellation.OWNER_CANCELLED));
        assertThat(row(id)).containsEntry("status", "EXPIRED").containsEntry("cancel_requested_at", null);
        assertThat(expireJobs(id)).isZero();
    }

    // ---- the checkout.expire job

    @Test
    void theJobExpiresTheProvidersSessionWithAStableKey() throws Exception {
        String id = open();
        cancel(owner, id);
        expire(id);
        assertThat(fakeEt.expireCalls()).isEqualTo(1);
        assertThat(fakeEt.lastExpireKey()).isEqualTo("checkout-expire:" + id);
        assertThat(fakeEt.expireRanInTransaction()).isFalse();
    }

    @Test
    void theJobLeavesCancelledToReconciliation() throws Exception {
        String id = open();
        cancel(owner, id);
        expire(id);
        assertThat(row(id)).containsEntry("status", "CANCEL_REQUESTED");
    }

    @Test
    void ac4AlreadyCompleteLeavesThePaymentToTheMatrix() throws Exception {
        String id = open();
        cancel(owner, id);
        Map<String, Object> before = row(id);
        fakeEt.script(Script.ALREADY_COMPLETE_ON_EXPIRE);
        expire(id);
        assertThat(row(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from payment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, owner.businessId())).isZero();
    }

    @Test
    void ac3AFailingExpireRetries() throws Exception {
        String id = open();
        cancel(owner, id);
        fakeEt.script(Script.EXPIRE_TIMEOUT);
        assertThatThrownBy(() -> expire(id)).isInstanceOf(TransientJobException.class);
        assertThat(row(id)).containsEntry("status", "CANCEL_REQUESTED");
        assertThat(JobFailurePolicy.isFinancial(CheckoutExpireHandler.TYPE)).isTrue();
    }

    @Test
    void ac3RetriesStopAtExpiry() throws Exception {
        String id = open();
        cancel(owner, id);
        fakeEt.script(Script.EXPIRE_TIMEOUT);
        clock.setOffset(Duration.ofHours(25));
        expire(id);
        assertThat(fakeEt.expireCalls()).isZero();
    }

    @Test
    void ac3TheLastMomentBeforeExpiryStillTries() throws Exception {
        String id = open();
        cancel(owner, id);
        clock.setOffset(Duration.ofHours(23));
        expire(id);
        assertThat(fakeEt.expireCalls()).isEqualTo(1);
    }

    @Test
    void aFinalRefusalEndsTheJob() throws Exception {
        String id = open();
        cancel(owner, id);
        fakeEt.script(Script.EXPIRE_REFUSED);
        expire(id);
        assertThat(fakeEt.expireCalls()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "CANCEL_REQUESTED");
    }

    @Test
    void aProviderWithoutExpiryIsNotCalled() throws Exception {
        String id = open();
        cancel(owner, id);
        fakeEt.capabilities(new ProviderCapabilities(true, false, true, Duration.ofHours(24), true));
        expire(id);
        assertThat(fakeEt.expireCalls()).isZero();
    }

    @Test
    void theJobIgnoresARowNotAwaitingCancellation() throws Exception {
        String id = open();
        expire(id);
        expire("nope");
        assertThat(fakeEt.expireCalls()).isZero();
    }

    @Test
    void theJobRefusesToRunInsideATransaction() throws Exception {
        String id = open();
        cancel(owner, id);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> expire(id))).isInstanceOf(ProviderCallInTransactionException.class);
    }

    // ---- races the story names

    @RepeatedTest(5)
    void cancelRacingCreationNeverLeavesAUrlOnACancelledCheckout() throws Exception {
        perform(owner, "seed", otherPrice).andExpect(status().isCreated());
        String seed = (String) only().get("id");
        cancel(owner, seed);
        var results = race(2, i -> i == 0 ? () -> httpStatus(owner, "k", price) : () -> {
            List<String> ids = jdbc.queryForList("select id from checkout_session where status in ('CREATING', 'OPEN')", String.class);
            return ids.isEmpty() ? 200 : cancel(owner, ids.get(0)).andReturn().getResponse().getStatus();
        });
        assertThat(results).doesNotContain(500);
        for (String id : jdbc.queryForList("select id from checkout_session where status = 'CANCEL_REQUESTED'", String.class)) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/billing/checkouts/{id}", id).header("Authorization", data.bearer(owner))).andExpect(jsonPath("$.redirectUrl").doesNotExist());
        }
    }

    @Test
    void concurrentCancelsRequestOnce() throws Exception {
        String id = open();
        int v = version(id);
        var results = race(8, i -> () -> cancel(owner, id).andReturn().getResponse().getStatus());
        assertThat(results).containsOnly(200);
        assertThat(version(id)).isEqualTo(v + 1);
        assertThat(expireJobs(id)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void aConfigurationChangeRacingCreationLeavesNoActiveCheckoutOfADisabledPlan() throws Exception {
        int version = jdbc.queryForObject("select max(version) from monetization_config", Integer.class);
        var results = race(2, i -> i == 0 ? () -> httpStatus(owner, "k", price) : () -> {
            change("SETTING_2", List.of("MODEL_2"), version);
            return 200;
        });
        assertThat(results).doesNotContain(500);
        assertThat(jdbc.queryForObject("select count(*) from checkout_session where status in ('CREATING', 'OPEN')", Integer.class)).isZero();
    }
}
