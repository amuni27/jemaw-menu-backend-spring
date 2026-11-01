package com.agafari.com.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** AGF E4-M4: direct rows for monetization tests (configuration versions, subscriptions, plan requests). */
public final class MonetizationFixtures {

    private MonetizationFixtures() {
    }

    public static void config(JdbcTemplate jdbc, int version, String setting, boolean m2, boolean m3, boolean m4, String adminId) {
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, "
                + "created_at, created_by_user_id) values (?, ?, ?, ?, ?, now(), now(), ?)", version, setting, m2, m3, m4, adminId);
    }

    public static String subscription(JdbcTemplate jdbc, String businessId, String plan, int configVersion, String adminId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, ?, 'ACTIVE', ?, 'MANUAL', now(), ?, now(), ?, 0)",
                id, businessId, plan, configVersion, adminId, adminId);
        return id;
    }

    public static String pendingRequest(JdbcTemplate jdbc, String businessId, String plan, int configVersion, String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into plan_request (id, business_id, plan_code, config_version, status, requested_at, requested_by_user_id, row_version) "
                + "values (?, ?, ?, ?, 'PENDING', now(), ?, 0)", id, businessId, plan, configVersion, ownerId);
        return id;
    }
}
