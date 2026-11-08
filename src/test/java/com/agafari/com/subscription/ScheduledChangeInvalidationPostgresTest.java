package com.agafari.com.subscription;

import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

/** AGF H2 regression: the same scenarios as on H2, on PostgreSQL, in a schema of their own. */
class ScheduledChangeInvalidationPostgresTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;

    private Connection connection;

    @BeforeEach
    void open() throws SQLException {
        connection = dataSource.getConnection();
        try (Statement s = connection.createStatement()) {
            s.execute("create schema if not exists invalidation_probe");
            s.execute("set search_path to invalidation_probe");
        }
    }

    @AfterEach
    void close() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("set search_path to public");
            s.execute("drop schema if exists invalidation_probe cascade");
        }
        connection.close();
    }

    @TestFactory
    Stream<DynamicTest> onPostgres() {
        return new ScheduledChangeInvalidationScenarios(new SingleConnectionDataSource(connection, true)).all();
    }
}
