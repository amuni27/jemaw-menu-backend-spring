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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF E4-M7 (AC13): V17 adds the Agafari ad inventory and the served-impression aggregate. Existing data untouched; fresh
 * and upgraded databases get the same objects; the database enforces the minimum policy (lengths, https links, end after
 * start, image with alt text), no deletes, archived ads frozen, and impression counts never negative.
 */
class AgafariAdMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "agafari_ad_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

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
    void freshAndUpgradedDatabasesHaveTheAdObjectsAndTheFullHistory() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").load().migrate();
        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22");
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = ? and table_name in ('agafari_ad', 'ad_impression_daily')",
                    String.class, schema)).as(schema).containsExactlyInAnyOrder("agafari_ad", "ad_impression_daily");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.triggers where trigger_schema = ? and trigger_name = 'agafari_ad_guard'",
                    Integer.class, schema)).as(schema).isPositive();
        }
    }

    private String insert(String title, String link, String imageKey, String imageAlt, String ends) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into agafari_ad (id, title, body, link_url, image_key, image_alt, starts_at, ends_at, schedule_time_zone, status, created_at, created_by, "
                + "updated_at, updated_by, row_version) values (?, ?, 'Text', ?, ?, ?, '2026-12-01T09:00:00Z', " + ends + ", 'UTC', 'ACTIVE', now(), 'a', now(), 'a', 0)",
                id, title, link, imageKey, imageAlt);
        return id;
    }

    @Test
    void theDatabaseEnforcesThePolicyAndTheLifecycle() {
        assertThatThrownBy(() -> insert("x".repeat(61), "https://e.test", null, null, "null")).hasMessageContaining("ck_agafari_ad_title");
        assertThatThrownBy(() -> insert("Ok", "http://e.test", null, null, "null")).hasMessageContaining("ck_agafari_ad_link");
        assertThatThrownBy(() -> insert("Ok", "https://e.test", "agafari-ad-asset/x/a.png", null, "null")).hasMessageContaining("ck_agafari_ad_image");
        assertThatThrownBy(() -> insert("Ok", "https://e.test", null, null, "'2026-12-01T09:00:00Z'")).hasMessageContaining("ck_agafari_ad_schedule");

        String id = insert("Ok", "https://e.test", null, null, "null");
        assertThatThrownBy(() -> jdbc.update("delete from agafari_ad where id = ?", id)).hasMessageContaining("can't be deleted");
        jdbc.update("update agafari_ad set status = 'ARCHIVED' where id = ?", id);
        assertThatThrownBy(() -> jdbc.update("update agafari_ad set title = 'Changed' where id = ?", id)).hasMessageContaining("archived ads can't be changed");

        Tenant t = data.tenant("ads-migration");
        assertThatThrownBy(() -> jdbc.update("insert into ad_impression_daily (ad_id, business_id, view_date, impression_count) values (?, ?, current_date, -1)",
                id, t.businessId())).hasMessageContaining("ck_ad_impression_daily_count");
    }
}
