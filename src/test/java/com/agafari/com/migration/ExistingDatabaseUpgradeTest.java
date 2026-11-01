package com.agafari.com.migration;

import com.agafari.com.config.FlywayBaselineGuard;
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
 * Decision A: existing development databases (built by {@code ddl-auto}) are kept with their data. Flyway records them
 * at version 1 (baseline) without running V1, and V1 builds exactly the same columns, so such a database and a fresh
 * one are interchangeable for future V2+ migrations.
 */
class ExistingDatabaseUpgradeTest extends AbstractIntegrationTest {

    private static final String LEGACY = "legacy_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void anExistingDevelopmentDatabaseWithData() throws Exception {
        jdbc.execute("drop schema if exists " + LEGACY + " cascade");
        jdbc.execute("create schema " + LEGACY);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + LEGACY);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("insert into users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") "
                    + "values ('u1', 'dev@test.local', 'Dev Owner', 'hash', 'VENUE', 'ACTIVE', now(), now())");
            c.createStatement().execute("insert into menu_type (id, name, \"createdAt\", \"updatedAt\") values ('t1', 'Lunch', now(), now())");
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropLegacySchema() {
        jdbc.execute("drop schema if exists " + LEGACY + " cascade");
    }

    private Flyway appLikeFlyway() {
        // same settings as the default (development) profile in application.yml
        return Flyway.configure().dataSource(dataSource).schemas(LEGACY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load();
    }

    @Test
    void theExistingDatabaseIsBaselinedAtVersion1WithoutRunningV1AndKeepsItsData() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            FlywayBaselineGuard.verify(c, LEGACY); // passes: the core tables are there
        }
        appLikeFlyway().migrate();

        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, type, success from " + LEGACY + ".flyway_schema_history order by installed_rank");
        assertThat(history).hasSize(12); // V2 (S1-09 seed), V3 (S2-12 column), V4 (CR1 country), V5 (S2-26 zone), V6 (S2-23 Amharic), V7 (S2-25 specials) and V8 (S2-36 category specials), V9 (S2-24 menu views), V10 (S2-22 email verification), V11 (E4-M1 admin audit) and V12 (E4-M2 monetization config) then run on the baselined database
        assertThat(history.get(0)).containsEntry("version", "1").containsEntry("type", "BASELINE").containsEntry("success", true);
        assertThat(history.get(1)).containsEntry("version", "2").containsEntry("type", "SQL").containsEntry("success", true);

        assertThat(jdbc.queryForObject("select email from " + LEGACY + ".users where id = 'u1'", String.class)).isEqualTo("dev@test.local");
        assertThat(jdbc.queryForObject("select name from " + LEGACY + ".menu_type where id = 't1'", String.class)).isEqualTo("Lunch");
    }

    @Test
    void runningFlywayAgainChangesNothing() {
        appLikeFlyway().migrate();
        appLikeFlyway().migrate();
        assertThat(jdbc.queryForObject("select count(*) from " + LEGACY + ".flyway_schema_history", Integer.class)).isEqualTo(12); // baseline + V2..V12
        assertThat(jdbc.queryForObject("select count(*) from " + LEGACY + ".users", Integer.class)).isEqualTo(1);
    }

    @Test
    void anUpgradedExistingDatabaseHasExactlyTheColumnsOfAFreshOne() {
        appLikeFlyway().migrate(); // baseline at V1, then V2.. (AGF-S2-12 adds a column in V3)
        String columns = "select table_name, column_name, data_type, is_nullable, character_maximum_length, numeric_precision, "
                + "numeric_scale, datetime_precision from information_schema.columns where table_schema = ? "
                + "and table_name <> 'flyway_schema_history' order by table_name, column_name";
        List<Map<String, Object>> fromV1 = jdbc.queryForList(columns, "public");
        List<Map<String, Object>> fromDdlAuto = jdbc.queryForList(columns, LEGACY);
        assertThat(fromV1).isNotEmpty().isEqualTo(fromDdlAuto);
    }

    @Test
    void anUpgradedExistingDatabaseHasTheSameKeysUniqueRulesAndChecks() {
        appLikeFlyway().migrate();
        String constraints = "select tc.table_name, tc.constraint_type, string_agg(kcu.column_name, ',' order by kcu.ordinal_position) cols "
                + "from information_schema.table_constraints tc left join information_schema.key_column_usage kcu "
                + "on tc.constraint_name = kcu.constraint_name and tc.table_schema = kcu.table_schema "
                + "where tc.table_schema = ? and tc.table_name <> 'flyway_schema_history' and tc.constraint_type in ('PRIMARY KEY','UNIQUE','FOREIGN KEY') "
                + "group by tc.table_name, tc.constraint_type, tc.constraint_name order by 1, 2, 3";
        assertThat(jdbc.queryForList(constraints, "public")).isEqualTo(jdbc.queryForList(constraints, LEGACY));

        String checks = "select rel.relname, pg_get_constraintdef(con.oid) from pg_constraint con join pg_class rel on rel.oid = con.conrelid "
                + "join pg_namespace ns on ns.oid = rel.relnamespace where ns.nspname = ? and con.contype = 'c' order by 1, 2";
        assertThat(jdbc.queryForList(checks, "public")).isNotEmpty().isEqualTo(jdbc.queryForList(checks, LEGACY));
    }
}
