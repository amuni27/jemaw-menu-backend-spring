package com.agafari.com.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** AGF-S1-10: handler-level checks for errors that are hard to trigger through HTTP. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void dataIntegrityViolationBecomesGeneric409WithoutDatabaseDetails() {
        var ex = new DataIntegrityViolationException(
                "could not execute statement; SQL [n/a]; constraint [uk_users_email]; "
                        + "ERROR: duplicate key value violates unique constraint \"uk_users_email\" Detail: Key (email)=(owner@example.com)");

        var response = handler.handleDataIntegrity(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("Request conflicts with existing data");
        assertThat(response.getBody().toString()).doesNotContain("uk_users_email", "owner@example.com", "SQL", "duplicate key");
    }
}
