package com.agafari.com.migration;

import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision A (provisional baseline): a fresh database is built by Flyway's V1 migration, and the application starts on
 * it with {@code ddl-auto: validate}. Every other integration test runs on this Flyway-built schema too, so the whole
 * suite proves V1 matches the entities.
 */
class FlywayBaselineTest extends AbstractIntegrationTest {

    static final List<String> CORE_TABLES =
            List.of("business", "business_hours", "category", "menu", "menu_item", "menu_type", "users");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void theFreshTestDatabaseWasBuiltByVersion1() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, type, success from flyway_schema_history where version is not null order by installed_rank");
        assertThat(history).isNotEmpty();
        Map<String, Object> v1 = history.get(0);
        assertThat(v1.get("version")).isEqualTo("1");
        assertThat(v1.get("description")).isEqualTo("baseline");
        assertThat(v1.get("type")).isEqualTo("SQL");
        assertThat(v1.get("success")).isEqualTo(true);
    }

    // AGF-S1-09: the menu-type seed follows the baseline
    @Test
    void version2SeedFollowsTheBaseline() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, success from flyway_schema_history where version is not null order by installed_rank");
        assertThat(history).extracting(r -> r.get("version")).containsExactly("1", "2", "3", "4", "5", "6", "7", "8"); // V3: AGF-S2-12, V4: CR1 country, V5: S2-26 time zone, V6: S2-23 Amharic, V7: S2-25 specials, V8: S2-36 category specials
        assertThat(history.get(1).get("description")).isEqualTo("seed menu types");
        assertThat(history.get(1).get("success")).isEqualTo(true);
    }

    @Test
    void allApplicationTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = current_schema() and table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).containsAll(CORE_TABLES).contains("flyway_schema_history");
    }

    @Test
    void hibernateValidatesTheMigratedSchemaInsteadOfChangingIt() {
        assertThat(jdbc.queryForObject("show search_path", String.class)).isNotBlank();
        // ddl-auto is 'validate' in the test profile: a mismatch would have stopped the context before any test ran
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class)).isZero();
    }
}
