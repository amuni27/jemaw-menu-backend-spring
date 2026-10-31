package com.agafari.com.migration;

import com.agafari.com.config.FlywayBaselineGuard;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision A (S1-08A plan §6 "startup safety check"): Flyway may only baseline a non-empty schema that really is an
 * Agafari database. A non-empty schema without the core tables (e.g. the wrong database) stops startup instead of being
 * recorded at version 1.
 */
class FlywayBaselineGuardTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void dropSchemas() {
        jdbc.execute("drop schema if exists guard_wrong cascade");
        jdbc.execute("drop schema if exists guard_empty cascade");
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
}
