package com.agafari.com.migration;

import com.agafari.com.config.FlywayBaselineGuard;
import com.agafari.com.config.FlywayConfig;
import com.agafari.com.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision A (S1-08A plan §6 "startup safety check"): Flyway may only baseline a non-empty schema that really is an
 * Agafari database. A non-empty schema without the core tables (e.g. the wrong database) stops startup instead of being
 * recorded at version 1.
 *
 * <p>AGF-S1-08B hardening (drift checks D12 and D14, S1-08B offline rehearsal F2/F4): the guard also refuses a schema
 * whose menu types collide with the V2 seed ids, and a schema without history that already has objects V3–V8 create.
 * It only reads; it never repairs anything.
 */
class FlywayBaselineGuardTest extends AbstractIntegrationTest {

    private static final String LEGACY = "guard_legacy";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void dropSchemas() {
        jdbc.execute("drop schema if exists guard_wrong cascade");
        jdbc.execute("drop schema if exists guard_empty cascade");
        jdbc.execute("drop schema if exists " + LEGACY + " cascade");
    }

    @Test
    void aNonEmptySchemaWithoutTheCoreTablesIsRefused() throws Exception {
        jdbc.execute("create schema guard_wrong");
        jdbc.execute("create table guard_wrong.invoices (id int primary key)");
        try (Connection c = dataSource.getConnection()) {
            assertThatThrownBy(() -> FlywayBaselineGuard.verify(c, "guard_wrong"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not an Agafari database")
                    .hasMessageContaining("users");
        }
    }

    @Test
    void anEmptySchemaIsFineBecauseFlywayWillRunVersion1() throws Exception {
        jdbc.execute("create schema guard_empty");
        try (Connection c = dataSource.getConnection()) {
            assertThatCode(() -> FlywayBaselineGuard.verify(c, "guard_empty")).doesNotThrowAnyException();
        }
    }

    @Test
    void aSchemaThatAlreadyHasFlywayHistoryIsLeftToFlyway() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThatCode(() -> FlywayBaselineGuard.verify(c, "public")).doesNotThrowAnyException();
        }
    }

    @Test
    void aCleanExistingDatabaseStillPasses() throws Exception {
        legacySchema();
        jdbc.execute("insert into " + LEGACY + ".menu_type (id, name, \"createdAt\", \"updatedAt\") values "
                + "('legacy-lunch', 'lunch', now(), now()), ('mt-drinks', 'DRINKS', now(), now())"); // same name, other case: V2 skips it
        try (Connection c = dataSource.getConnection()) {
            assertThatCode(() -> FlywayBaselineGuard.verify(c, LEGACY)).doesNotThrowAnyException();
        }
    }

    // ---- D12: menu-type id collision with the V2 seed ----

    @Test
    void d12AMenuTypeUsingAV2SeedIdWithAnotherNameIsRefused() throws Exception {
        legacySchema();
        jdbc.execute("insert into " + LEGACY + ".menu_type (id, name, \"createdAt\", \"updatedAt\") values ('mt-drinks', 'Beverages', now(), now())");
        try (Connection c = dataSource.getConnection()) {
            assertThatThrownBy(() -> FlywayBaselineGuard.verify(c, LEGACY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("D12")
                    .hasMessageContaining("mt-drinks")
                    .hasMessageContaining("Beverages");
        }
        assertThat(jdbc.queryForObject("select name from " + LEGACY + ".menu_type where id = 'mt-drinks'", String.class))
                .isEqualTo("Beverages"); // nothing repaired
    }

    @Test
    void d12AlsoAppliesToADatabaseBaselinedBeforeV2Existed() throws Exception {
        legacySchema();
        jdbc.execute("insert into " + LEGACY + ".menu_type (id, name, \"createdAt\", \"updatedAt\") values ('mt-kids', 'Children', now(), now())");
        Flyway.configure().dataSource(dataSource).schemas(LEGACY).locations("classpath:db/migration")
                .baselineVersion("1").load().baseline(); // an older development database: history at V1 only
        try (Connection c = dataSource.getConnection()) {
            assertThatThrownBy(() -> FlywayBaselineGuard.verify(c, LEGACY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mt-kids");
        }
    }

    @Test
    void d12IsNotCheckedOnceV2HasRun() throws Exception {
        legacySchema();
        appLikeFlyway().migrate(); // baseline + V2..V8
        jdbc.execute("update " + LEGACY + ".menu_type set name = 'Beverages' where id = 'mt-drinks'"); // an owner-side rename later is not D12
        try (Connection c = dataSource.getConnection()) {
            assertThatCode(() -> FlywayBaselineGuard.verify(c, LEGACY)).doesNotThrowAnyException();
        }
    }

    @Test
    void theGuardKnowsExactlyTheIdsAndNamesV2Seeds() throws Exception {
        String v2 = new ClassPathResource("db/migration/V2__seed_menu_types.sql").getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> seeded = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\\('(mt-[a-z-]+)',\\s*'([^']+)'").matcher(v2);
        while (m.find()) seeded.put(m.group(1), m.group(2));
        assertThat(seeded).hasSize(9);
        assertThat(FlywayBaselineGuard.V2_SEED_MENU_TYPES).isEqualTo(seeded);
    }

    // ---- D14: objects of V3..V8 already present before the first Flyway start ----

    @Test
    void d14ASchemaWithoutHistoryThatAlreadyHasLaterMigrationObjectsIsRefused() throws Exception {
        legacySchema();
        // what an accidental start with Flyway off and ddl-auto update leaves behind (S1-08B rehearsal R1)
        jdbc.execute("alter table " + LEGACY + ".business add column location_url varchar(2048)");
        jdbc.execute("alter table " + LEGACY + ".menu add column name_am varchar(255)");
        jdbc.execute("create table " + LEGACY + ".special (id varchar(255) primary key)");
        try (Connection c = dataSource.getConnection()) {
            assertThatThrownBy(() -> FlywayBaselineGuard.verify(c, LEGACY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("D14")
                    .hasMessageContaining("business.location_url")
                    .hasMessageContaining("menu.name_am")
                    .hasMessageContaining("special");
        }
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = ? "
                + "and table_name = 'flyway_schema_history'", Integer.class, LEGACY)).isZero(); // nothing recorded
    }

    @Test
    void d14AlsoCatchesALeftoverTriggerFunction() throws Exception {
        legacySchema();
        jdbc.execute("create function " + LEGACY + ".special_business_matches_dish() returns trigger language plpgsql as $$ begin return new; end $$");
        try (Connection c = dataSource.getConnection()) {
            assertThatThrownBy(() -> FlywayBaselineGuard.verify(c, LEGACY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("special_business_matches_dish");
        }
    }

    @Test
    void theD14ListNamesRealObjectsOfV3ToV8() throws Exception {
        // every listed object exists in the fresh test database (V1..V8) ...
        for (String column : FlywayBaselineGuard.LATER_MIGRATION_COLUMNS) {
            String[] tc = column.split("\\.");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = 'public' "
                    + "and table_name = ? and column_name = ?", Integer.class, tc[0], tc[1])).as(column).isEqualTo(1);
        }
        for (String table : FlywayBaselineGuard.LATER_MIGRATION_TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public' "
                    + "and table_name = ?", Integer.class, table)).as(table).isEqualTo(1);
        }
        for (String function : FlywayBaselineGuard.LATER_MIGRATION_FUNCTIONS) {
            assertThat(jdbc.queryForObject("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                    + "where n.nspname = 'public' and p.proname = ?", Integer.class, function)).as(function).isEqualTo(1);
        }
        // ... and none of them is part of V1 (the legacy ddl-auto fixture is what V1 reproduces)
        String fixture = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String column : FlywayBaselineGuard.LATER_MIGRATION_COLUMNS) {
            assertThat(fixture).doesNotContain(column.split("\\.")[1] + " ");
        }
    }

    // ---- read-only, and before any baseline or migration ----

    @Test
    void theGuardOnlyReadsItWorksInsideAReadOnlyTransaction() throws Exception {
        legacySchema();
        jdbc.execute("insert into " + LEGACY + ".menu_type (id, name, \"createdAt\", \"updatedAt\") values ('mt-drinks', 'Beverages', now(), now())");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true); // PostgreSQL rejects any write in this transaction
            try {
                assertThatThrownBy(() -> FlywayBaselineGuard.verify(c, LEGACY))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("D12"); // refused by the check, not by a failed write
                assertThatCode(() -> FlywayBaselineGuard.verify(c, "public")).doesNotThrowAnyException();
                // control: this transaction really rejects writes, so the guard cannot have written anything
                assertThatThrownBy(() -> c.createStatement().execute("create table " + LEGACY + ".probe (id int)"))
                        .hasMessageContaining("read-only transaction");
            } finally {
                c.rollback();
                c.setReadOnly(false);
                c.setAutoCommit(true);
            }
        }
    }

    @Test
    void theAppMigrationStrategyRunsTheGuardBeforeFlywayBaselinesOrMigrates() {
        legacySchema();
        jdbc.execute("insert into " + LEGACY + ".menu_type (id, name, \"createdAt\", \"updatedAt\") values ('mt-drinks', 'Beverages', now(), now())");
        jdbc.execute("alter table " + LEGACY + ".business add column country varchar(2)");

        assertThatThrownBy(() -> new FlywayConfig().guardedMigration().migrate(appLikeFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("D12")
                .hasMessageContaining("D14");

        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = ? "
                + "and table_name = 'flyway_schema_history'", Integer.class, LEGACY)).isZero(); // no baseline, no migration
        assertThat(jdbc.queryForObject("select count(*) from " + LEGACY + ".menu_type", Integer.class)).isEqualTo(1); // V2 never ran
        List<String> businessColumns = jdbc.queryForList("select column_name from information_schema.columns where "
                + "table_schema = ? and table_name = 'business'", String.class, LEGACY);
        assertThat(businessColumns).contains("country").doesNotContain("time_zone", "location_url"); // left exactly as found
    }

    private Flyway appLikeFlyway() {
        // same settings as application.yml (Decision A); the prod profile would add only flyway.enabled
        return Flyway.configure().dataSource(dataSource).schemas(LEGACY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load();
    }

    /** An existing database as ddl-auto built it (what V1 reproduces), without Flyway history. */
    private void legacySchema() {
        jdbc.execute("drop schema if exists " + LEGACY + " cascade");
        jdbc.execute("create schema " + LEGACY);
        try (Connection c = dataSource.getConnection()) {
            String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
            c.createStatement().execute("set search_path to " + LEGACY);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("set search_path to public");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
