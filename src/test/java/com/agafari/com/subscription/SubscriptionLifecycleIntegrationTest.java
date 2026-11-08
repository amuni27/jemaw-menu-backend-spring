package com.agafari.com.subscription;

import com.agafari.com.dto.request.PlanRequestFulfilRequest;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF SUB-R2-02: on real PostgreSQL the lifecycle refuses to write without the locks (AC2), and the audit rows of the two
 * delegated paths stay exactly as they were (AC3; these two assertions also passed on the pre-delegation code).
 */
class SubscriptionLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private MonetizationLocks locks;
    @Autowired private PlanRequestService planRequests;
    @Autowired private PlatformTransactionManager transactionManager;

    private User admin;
    private Tenant owner;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        admin = data.admin("lifecycle-admin");
        owner = data.tenant("lifecycle-owner");
        jdbc.update("update users set email_verified_at = now() where id = ?", owner.owner().getId());
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelfServiceEnabled(true);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, owner.businessId());
    }

    private ManualRecord manual() {
        return new ManualRecord("MODEL_4", 1, null, Instant.now());
    }

    @Test
    void outsideATransactionNothingIsWritten() {
        assertThatThrownBy(() -> lifecycle.recordManual(owner.businessId(), manual(), new Actor(admin.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void withoutTheLocksNothingIsWritten() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> lifecycle.recordManual(owner.businessId(), manual(), new Actor(admin.getId()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            locks.configShared();
            lifecycle.startOwnerFree(owner.businessId(), "MODEL_2", 1, Instant.now(), new Actor(owner.owner().getId()));
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void withBothLocksTheRowIsCommitted() {
        tx.executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            lifecycle.recordManual(owner.businessId(), manual(), new Actor(admin.getId()));
        });
        assertThat(jdbc.queryForMap("select plan_code, status, source from business_subscription where business_id = ?", owner.businessId()))
                .containsEntry("plan_code", "MODEL_4").containsEntry("status", "ACTIVE").containsEntry("source", "MANUAL");
    }

    @Test
    void theOwnerActivationAuditRowIsUnchanged() throws Exception {
        mockMvc.perform(post("/api/plan/activations").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}")).andExpect(status().is2xxSuccessful());
        String id = jdbc.queryForObject("select id from business_subscription where business_id = ?", String.class, owner.businessId());
        Map<String, Object> audit = jdbc.queryForMap("select target_type, target_id, detail from admin_audit_event where action = 'OWNER_SUBSCRIPTION_ACTIVATED'");
        assertThat(audit).containsEntry("target_type", "BUSINESS_SUBSCRIPTION").containsEntry("target_id", id)
                .containsEntry("detail", "MODEL_2 config=1 source=OWNER");
    }

    @Test
    void theFulfilmentAuditRowsAreUnchanged() {
        String request = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        planRequests.fulfil(request, new PlanRequestFulfilRequest(1, null, 0), admin.getId(), "admin@example.test");
        String id = jdbc.queryForObject("select id from business_subscription where business_id = ?", String.class, owner.businessId());
        Map<String, Object> created = jdbc.queryForMap("select target_type, target_id, detail from admin_audit_event where action = 'SUBSCRIPTION_CREATED'");
        assertThat(created).containsEntry("target_type", "BUSINESS_SUBSCRIPTION").containsEntry("target_id", id)
                .containsEntry("detail", "MODEL_4 ACTIVE source=MANUAL config=1 periodEnd=null");
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'PLAN_REQUEST_FULFILLED'", String.class))
                .isEqualTo("MODEL_4 config=1 subscription=" + id + " source=MANUAL");
    }
}
