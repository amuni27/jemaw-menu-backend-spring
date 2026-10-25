package com.agafari.com.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;

/** Decision A: run the baseline guard before every Flyway migration (only active where Flyway is enabled). */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy guardedMigration() {
        return flyway -> {
            try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
                String[] schemas = flyway.getConfiguration().getSchemas();
                String schema = schemas.length > 0 ? schemas[0] : c.getSchema();
                FlywayBaselineGuard.verify(c, schema);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Could not check the database before migrating", e);
            }
            flyway.migrate();
        };
    }
}
