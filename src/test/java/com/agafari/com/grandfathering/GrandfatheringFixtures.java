package com.agafari.com.grandfathering;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** AGF E4-M6: a manual ACTIVE subscription with a chosen start and (optional) period end, inserted directly. */
final class GrandfatheringFixtures {

    private GrandfatheringFixtures() {
    }

    static String manual(JdbcTemplate jdbc, String businessId, String plan, int configVersion, Instant periodEnd, Instant createdAt, String adminId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, 'ACTIVE', ?, ?, 'MANUAL', ?, ?, ?, ?, 0)",
                id, businessId, plan, configVersion, periodEnd == null ? null : Timestamp.from(periodEnd), Timestamp.from(createdAt), adminId,
                Timestamp.from(createdAt), adminId);
        return id;
    }
}
