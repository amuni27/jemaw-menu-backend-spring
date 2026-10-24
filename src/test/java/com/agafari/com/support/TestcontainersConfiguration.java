package com.agafari.com.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Throwaway PostgreSQL for integration tests (requires Docker).
 * Set AGAFARI_TEST_EXTERNAL_DB=true to use an already-running PostgreSQL instead
 * (connection from AGAFARI_TEST_DB_URL / _USERNAME / _PASSWORD, see application-test.yml).
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agafari.test.external-db", havingValue = "false", matchIfMissing = true)
public class TestcontainersConfiguration {

    // Production PostgreSQL version is unconfirmed; align once the M8 schema dump is available
    static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
