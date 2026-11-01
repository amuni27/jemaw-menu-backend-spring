package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF E4-M3 (AC7, AC7a, AC7b, D-M3-10/11): the database itself keeps one current subscription per restaurant (partial
 * unique index), freezes ended subscriptions, and refuses changes to plan, configuration version, restaurant and source,
 * even for direct SQL. Plans are read-only reference data.
 */
class SubscriptionDatabaseGuardTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private Tenant tenant;

    @BeforeEach
    void configured() {
        admin = data.admin("db-guard");
        tenant = data.tenant("db-guard-cafe");
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, "
                + "created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), ?)", admin.getId());
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, "
                + "created_at, created_by_user_id) values (2, 'SETTING_1', false, false, false, now(), now(), ?)", admin.getId());
    }

    private String insert(String plan, String status, int configVersion) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, ended_at, row_version) values (?, ?, ?, ?, ?, 'MANUAL', now(), ?, now(), ?, "
                + (status.equals("EXPIRED") ? "now()" : "null") + ", 0)", id, tenant.businessId(), plan, status, configVersion, admin.getId(), admin.getId());
        return id;
    }

    @Test
    void ac7OnlyOneCurrentSubscriptionPerRestaurantButEndedOnesMayAccumulate() {
        insert("MODEL_2", "EXPIRED", 1);
        insert("MODEL_3", "EXPIRED", 1);
        insert("MODEL_4", "ACTIVE", 1);
        assertThatThrownBy(() -> insert("MODEL_2", "ACTIVE", 1)).hasMessageContaining("uk_business_subscription_current");
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isEqualTo(3);
    }

    @Test
    void ac7aEndedSubscriptionsAreFrozenAndNothingCanBeDeleted() {
        String ended = insert("MODEL_2", "EXPIRED", 1);
        String current = insert("MODEL_4", "ACTIVE", 1);
        Map<String, Object> before = jdbc.queryForMap("select * from business_subscription where id = ?", ended);

        assertThatThrownBy(() -> jdbc.update("update business_subscription set period_end = now() + interval '1 day' where id = ?", ended))
                .hasMessageContaining("ended subscriptions can't be changed");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set status = 'ACTIVE', ended_at = null where id = ?", ended))
                .hasMessageContaining("ended subscriptions can't be changed");
        assertThatThrownBy(() -> jdbc.update("delete from business_subscription where id = ?", ended))
                .hasMessageContaining("subscriptions can't be deleted");
        assertThatThrownBy(() -> jdbc.update("delete from business_subscription where id = ?", current))
                .hasMessageContaining("subscriptions can't be deleted");
        assertThat(jdbc.queryForMap("select * from business_subscription where id = ?", ended)).isEqualTo(before);
    }

    @Test
    void ac7bPlanConfigurationRestaurantAndSourceCanNeverChange() {
        String current = insert("MODEL_2", "ACTIVE", 1);
        Tenant other = data.tenant("db-guard-other");
        for (String set : new String[]{"plan_code = 'MODEL_4'", "config_version = 2", "source = 'PROVIDER'",
                "business_id = '" + other.businessId() + "'", "created_at = now() - interval '1 day'"}) {
            assertThatThrownBy(() -> jdbc.update("update business_subscription set " + set + " where id = ?", current)).as(set)
                    .hasMessageContaining("can't be changed");
        }
        assertThatThrownBy(() -> jdbc.update("update business_subscription set status = 'GRANDFATHERED' where id = ?", current))
                .hasMessageContaining("only be ended");
        assertThat(jdbc.queryForMap("select plan_code, config_version, source, status from business_subscription where id = ?", current))
                .containsEntry("plan_code", "MODEL_2").containsEntry("config_version", 1).containsEntry("source", "MANUAL").containsEntry("status", "ACTIVE");
    }

    @Test
    void theAllowedChangesAreEndingAndAdjustingThePeriod() {
        String current = insert("MODEL_2", "ACTIVE", 1);
        jdbc.update("update business_subscription set period_end = now() + interval '30 days', row_version = row_version + 1 where id = ?", current);
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now(), row_version = row_version + 1 where id = ?", current);
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, current)).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = null where id = ?", current))
                .hasMessageContaining("can't be changed");
    }

    @Test
    void checksRefuseTrialsOutsideModel1AndInconsistentEndDates() {
        assertThatThrownBy(() -> insert("MODEL_2", "TRIAL", 1)).hasMessageContaining("ck_business_subscription_trial");
        assertThatThrownBy(() -> jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, ended_at, row_version) values ('x1', ?, 'MODEL_2', 'ACTIVE', 1, 'MANUAL', now(), ?, now(), ?, now(), 0)",
                tenant.businessId(), admin.getId(), admin.getId())).hasMessageContaining("ck_business_subscription_ended");
        assertThatThrownBy(() -> jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values ('x2', ?, 'MODEL_2', 'ACTIVE', 99, 'MANUAL', now(), ?, now(), ?, 0)",
                tenant.businessId(), admin.getId(), admin.getId())).hasMessageContaining("fk_business_subscription_config");
    }

    @Test
    void plansAreReadOnlyReferenceData() {
        assertThat(jdbc.queryForList("select code || ':' || model || ':' || setting || ':' || ad_source || ':' || paid from subscription_plan order by code", String.class))
                .containsExactly("MODEL_1:MODEL_1:SETTING_1:NONE:true", "MODEL_2:MODEL_2:SETTING_2:AGAFARI:false",
                        "MODEL_3:MODEL_3:SETTING_2:RESTAURANT:true", "MODEL_4:MODEL_4:SETTING_2:NONE:true");
        assertThatThrownBy(() -> jdbc.update("update subscription_plan set paid = false where code = 'MODEL_4'")).hasMessageContaining("subscription_plan is read-only");
        assertThatThrownBy(() -> jdbc.update("delete from subscription_plan where code = 'MODEL_4'")).hasMessageContaining("subscription_plan is read-only");
    }
}
