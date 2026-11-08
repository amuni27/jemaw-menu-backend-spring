package com.agafari.com.migration;

import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF E4-M8 (AC13, AC9): V18 adds the restaurant ad inventory. Fresh and upgraded databases get the same objects; the
 * database enforces the content limits, the owning restaurant (never changed), the suspension reason (present exactly when
 * suspended), the lifecycle (owner ACTIVE ↔ PAUSED and archive; suspend and reinstate only with the Super Admin marker;
 * never SUSPENDED → ACTIVE), archived ads frozen, and no direct delete (only the cascade from a deleted restaurant).
 */
class RestaurantAdMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "restaurant_ad_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void existingDatabase() throws Exception {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("create schema " + DEV);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    @Test
    void freshAndUpgradedDatabasesHaveTheRestaurantAdObjectsAndTheFullHistory() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").load().migrate();
        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23.1");
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = ? and table_name = 'restaurant_ad'",
                    String.class, schema)).as(schema).containsExactly("restaurant_ad");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.triggers where trigger_schema = ? and trigger_name = 'restaurant_ad_guard'",
                    Integer.class, schema)).as(schema).isPositive();
        }
    }

    private String insert(String businessId, String title, String link, String imageKey, String imageAlt, String ends, String status, String reason) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into restaurant_ad (id, business_id, title, body, link_url, image_key, image_alt, starts_at, ends_at, schedule_time_zone, status, "
                + "suspension_reason, suspended_at, created_at, created_by, updated_at, updated_by, row_version) values (?, ?, ?, 'Text', ?, ?, ?, "
                + "'2026-12-01T09:00:00Z', " + ends + ", 'Africa/Addis_Ababa', ?, ?, " + (reason == null ? "null" : "now()") + ", now(), 'o', now(), 'o', 0)",
                id, businessId, title, link, imageKey, imageAlt, status, reason);
        return id;
    }

    private String insert(String businessId) {
        return insert(businessId, "Ok", "https://e.test", null, null, "null", "ACTIVE", null);
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from restaurant_ad where id = ?", String.class, id);
    }

    /** One transaction with the Super Admin marker set (as the admin service does). */
    private void asAdmin(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbc.queryForObject("select set_config('agafari.ad_actor', 'ADMIN', true)", String.class);
            jdbc.update(sql, args);
        });
    }

    @Test
    void theDatabaseEnforcesTheContentLimitsAndTheSuspensionReason() {
        String b = data.tenant("ra-migration-limits").businessId();
        assertThatThrownBy(() -> insert(b, "x".repeat(61), "https://e.test", null, null, "null", "ACTIVE", null)).hasMessageContaining("ck_restaurant_ad_title");
        assertThatThrownBy(() -> insert(b, "Ok", "http://e.test", null, null, "null", "ACTIVE", null)).hasMessageContaining("ck_restaurant_ad_link");
        assertThatThrownBy(() -> insert(b, "Ok", "https://e.test", "restaurant-ad-asset/x/y/main.png", null, "null", "ACTIVE", null))
                .hasMessageContaining("ck_restaurant_ad_image");
        assertThatThrownBy(() -> insert(b, "Ok", "https://e.test", null, null, "'2026-12-01T09:00:00Z'", "ACTIVE", null)).hasMessageContaining("ck_restaurant_ad_schedule");
        assertThatThrownBy(() -> insert(b, "Ok", "https://e.test", null, null, "null", "LIVE", null)).hasMessageContaining("ck_restaurant_ad_status");
        assertThatThrownBy(() -> insert(b, "Ok", "https://e.test", null, null, "null", "SUSPENDED", null)).hasMessageContaining("ck_restaurant_ad_suspension");
        assertThatThrownBy(() -> insert(b, "Ok", "https://e.test", null, null, "null", "ACTIVE", "Misleading")).hasMessageContaining("ck_restaurant_ad_suspension");
        assertThatThrownBy(() -> insert(b, "Ok", "https://e.test", null, null, "null", "SUSPENDED", "x".repeat(501))).hasMessageContaining("ck_restaurant_ad_suspension");
        assertThatThrownBy(() -> insert("no-such-restaurant")).hasMessageContaining("fk_restaurant_ad_business");
    }

    @Test
    void theOwningRestaurantAndCreationNeverChangeAndAdsAreNeverDeletedDirectly() {
        Tenant a = data.tenant("ra-migration-a"), b = data.tenant("ra-migration-b");
        String id = insert(a.businessId());
        assertThatThrownBy(() -> jdbc.update("update restaurant_ad set business_id = ? where id = ?", b.businessId(), id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> asAdmin("update restaurant_ad set business_id = ? where id = ?", b.businessId(), id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> jdbc.update("update restaurant_ad set created_by = 'x' where id = ?", id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> jdbc.update("delete from restaurant_ad where id = ?", id)).hasMessageContaining("can't be deleted");

        // the cascade from a deleted restaurant is the only way rows go
        jdbc.update("delete from business where id = ?", a.businessId());
        assertThat(jdbc.queryForObject("select count(*) from restaurant_ad where id = ?", Integer.class, id)).isZero();
    }

    @Test
    void theLifecycleIsEnforcedAndOnlyTheSuperAdminMarkerSuspendsOrReinstates() {
        String b = data.tenant("ra-migration-lifecycle").businessId();
        String id = insert(b);
        jdbc.update("update restaurant_ad set status = 'PAUSED' where id = ?", id);
        jdbc.update("update restaurant_ad set status = 'ACTIVE' where id = ?", id);

        String suspend = "update restaurant_ad set status = 'SUSPENDED', suspension_reason = 'Misleading', suspended_at = now() where id = ?";
        assertThatThrownBy(() -> jdbc.update(suspend, id)).hasMessageContaining("only the Super Admin");
        asAdmin(suspend, id);
        assertThat(status(id)).isEqualTo("SUSPENDED");

        // an entitled owner may change the content of a suspended ad, never its status
        jdbc.update("update restaurant_ad set title = 'Fixed' where id = ?", id);
        String reinstate = "update restaurant_ad set status = 'PAUSED', suspension_reason = null, suspended_at = null where id = ?";
        assertThatThrownBy(() -> jdbc.update(reinstate, id)).hasMessageContaining("only the Super Admin");
        assertThatThrownBy(() -> asAdmin("update restaurant_ad set status = 'ACTIVE', suspension_reason = null, suspended_at = null where id = ?", id))
                .hasMessageContaining("invalid ad status change");
        asAdmin(reinstate, id);
        assertThat(status(id)).isEqualTo("PAUSED");

        // PAUSED can be suspended too; the owner may archive a suspended ad without the marker
        asAdmin(suspend, id);
        jdbc.update("update restaurant_ad set status = 'ARCHIVED', suspension_reason = null, suspended_at = null where id = ?", id);
        assertThat(status(id)).isEqualTo("ARCHIVED");
        assertThatThrownBy(() -> jdbc.update("update restaurant_ad set title = 'Again' where id = ?", id)).hasMessageContaining("archived ads can't be changed");
        assertThatThrownBy(() -> asAdmin(suspend, id)).hasMessageContaining("archived ads can't be changed");

        // the marker is transaction-local: it doesn't leak into the next statement on the same connection
        String other = insert(b);
        assertThatThrownBy(() -> jdbc.update(suspend, other)).hasMessageContaining("only the Super Admin");
    }
}
