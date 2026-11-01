package com.agafari.com.plan;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF E4-M4 (AC6, AC6a, AC7): database protections for plan requests, also against direct SQL: one pending request per
 * restaurant; no pending request beside a current subscription (I1 backstop); resolved requests frozen; restaurant, plan,
 * configuration version and requester immutable; no deletes.
 */
class PlanRequestDatabaseGuardTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        admin = data.admin("request-guard");
        tenant = data.tenant("request-guard-cafe");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        config(jdbc, 2, "SETTING_2", true, true, true, admin.getId());
    }

    private String resolve(String id, String status) {
        jdbc.update("update plan_request set status = ?, resolved_at = now(), resolved_by_user_id = ?, row_version = row_version + 1 where id = ?",
                status, admin.getId(), id);
        return id;
    }

    @Test
    void ac6OnePendingRequestPerRestaurantButResolvedOnesMayAccumulate() {
        resolve(pendingRequest(jdbc, tenant.businessId(), "MODEL_2", 1, tenant.owner().getId()), "WITHDRAWN");
        resolve(pendingRequest(jdbc, tenant.businessId(), "MODEL_3", 1, tenant.owner().getId()), "DECLINED");
        pendingRequest(jdbc, tenant.businessId(), "MODEL_4", 1, tenant.owner().getId());
        assertThatThrownBy(() -> pendingRequest(jdbc, tenant.businessId(), "MODEL_2", 1, tenant.owner().getId()))
                .hasMessageContaining("uk_plan_request_pending");
        assertThat(jdbc.queryForObject("select count(*) from plan_request", Integer.class)).isEqualTo(3);
    }

    @Test
    void ac6aNoPendingRequestBesideACurrentSubscription() {
        subscription(jdbc, tenant.businessId(), "MODEL_2", 1, admin.getId());
        assertThatThrownBy(() -> pendingRequest(jdbc, tenant.businessId(), "MODEL_4", 1, tenant.owner().getId()))
                .hasMessageContaining("current subscription");
    }

    @Test
    void ac7ResolvedRequestsAreFrozenAndNothingCanBeDeleted() {
        String withdrawn = resolve(pendingRequest(jdbc, tenant.businessId(), "MODEL_2", 1, tenant.owner().getId()), "WITHDRAWN");
        String pending = pendingRequest(jdbc, tenant.businessId(), "MODEL_3", 1, tenant.owner().getId());
        Map<String, Object> before = jdbc.queryForMap("select * from plan_request where id = ?", withdrawn);

        assertThatThrownBy(() -> jdbc.update("update plan_request set status = 'DECLINED' where id = ?", withdrawn))
                .hasMessageContaining("resolved plan requests can't be changed");
        assertThatThrownBy(() -> jdbc.update("delete from plan_request where id = ?", withdrawn)).hasMessageContaining("can't be deleted");
        assertThatThrownBy(() -> jdbc.update("delete from plan_request where id = ?", pending)).hasMessageContaining("can't be deleted");
        assertThat(jdbc.queryForMap("select * from plan_request where id = ?", withdrawn)).isEqualTo(before);
    }

    @Test
    void restaurantPlanConfigurationAndRequesterCanNeverChange() {
        String pending = pendingRequest(jdbc, tenant.businessId(), "MODEL_2", 1, tenant.owner().getId());
        Tenant other = data.tenant("request-guard-other");
        for (String set : new String[]{"plan_code = 'MODEL_4'", "config_version = 2", "business_id = '" + other.businessId() + "'",
                "requested_by_user_id = '" + admin.getId() + "'", "requested_at = now() - interval '1 day'"}) {
            assertThatThrownBy(() -> jdbc.update("update plan_request set " + set + " where id = ?", pending)).as(set)
                    .hasMessageContaining("can't be changed");
        }
    }

    @Test
    void checksKeepStatusesConsistent() {
        String pending = pendingRequest(jdbc, tenant.businessId(), "MODEL_2", 1, tenant.owner().getId());
        assertThatThrownBy(() -> jdbc.update("update plan_request set status = 'FULFILLED', resolved_at = now(), resolved_by_user_id = ? where id = ?",
                admin.getId(), pending)).hasMessageContaining("ck_plan_request_subscription");
        assertThatThrownBy(() -> jdbc.update("update plan_request set status = 'WITHDRAWN' where id = ?", pending))
                .hasMessageContaining("ck_plan_request_resolved");
        assertThatThrownBy(() -> pendingRequest(jdbc, data.tenant("model-one").businessId(), "MODEL_1", 1, tenant.owner().getId()))
                .hasMessageContaining("ck_plan_request_model");
        assertThatThrownBy(() -> jdbc.update("insert into plan_request (id, business_id, plan_code, config_version, status, requested_at, "
                + "requested_by_user_id, resolved_at, resolved_by_user_id, row_version) values ('r1', ?, 'MODEL_3', 1, 'DECLINED', now(), ?, now(), ?, 0)",
                data.tenant("born-resolved").businessId(), tenant.owner().getId(), admin.getId())).hasMessageContaining("new plan requests must be pending");
    }
}
