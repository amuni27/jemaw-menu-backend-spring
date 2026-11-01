package com.agafari.com.migration;

import com.agafari.com.support.AbstractIntegrationTest;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S2-22 (AC7, AC11): V10 adds users.email_verified_at (nullable). Owners who registered before verification existed
 * are grandfathered as verified (their registration time); new accounts start unverified. Other data is untouched.
 */
class EmailVerificationMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "emailverify_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void existingDatabaseWithAnOwner() throws Exception {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("create schema " + DEV);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("insert into users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") "
                    + "values ('u1', 'dev@test.local', 'Dev', 'x', 'VENUE', 'ACTIVE', timestamptz '2026-03-01 10:00:00+03', now())");
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private void migrateDev() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
    }

    @Test
    void existingOwnersAreGrandfatheredAsVerifiedAndKeepTheirData() {
        Map<String, Object> before = jdbc.queryForMap("select * from " + DEV + ".users where id = 'u1'");

        migrateDev();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        Map<String, Object> after = jdbc.queryForMap("select * from " + DEV + ".users where id = 'u1'");
        assertThat(after.remove("email_verified_at")).isEqualTo(before.get("createdAt"));
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theColumnIsNullableTimestampWithTimeZoneOnFreshAndUpgradedDatabases() {
        migrateDev();
        for (String schema : List.of("public", DEV)) {
            Map<String, Object> col = jdbc.queryForMap("select data_type, is_nullable, column_default from information_schema.columns "
                    + "where table_schema = ? and table_name = 'users' and column_name = 'email_verified_at'", schema);
            assertThat(col.get("data_type")).as(schema).isEqualTo("timestamp with time zone");
            assertThat(col.get("is_nullable")).as(schema).isEqualTo("YES");
            assertThat(col.get("column_default")).as(schema).isNull();
        }
    }

    @Test
    void anOwnerCreatedAfterV10StartsUnverified() {
        migrateDev();
        jdbc.update("insert into " + DEV + ".users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") "
                + "values ('u2', 'new@test.local', 'New', 'x', 'VENUE', 'ACTIVE', now(), now())");
        assertThat(jdbc.queryForObject("select email_verified_at from " + DEV + ".users where id = 'u2'", Object.class)).isNull();
    }
}
