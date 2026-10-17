package com.agafari.com.support;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-03: proves the integration-test foundation works end to end
 * (database, fixtures, JWT helper, MockMvc, mocked R2). Asserts current behavior only.
 */
class TestInfrastructureSmokeTest extends AbstractIntegrationTest {

    @Test
    void ownerSeesOnlyTheirOwnMenus() throws Exception {
        var a = data.tenant("alpha");
        var b = data.tenant("bravo");
        data.item(data.category(data.menu(a, "Alpha Lunch"), "Mains"), "Burger");
        data.item(data.category(data.menu(b, "Bravo Lunch"), "Mains"), "Pasta");

        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Alpha Lunch"));
    }

    @Test
    void publicMenuReturnsOnlyThatBusinessesItems() throws Exception {
        var a = data.tenant("alpha");
        var b = data.tenant("bravo");
        data.item(data.category(data.menu(a, "Alpha Lunch"), "Mains"), "Burger");
        data.item(data.category(data.menu(b, "Bravo Lunch"), "Mains"), "Pasta");

        mockMvc.perform(get("/venue/{businessId}/menu", a.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value("alpha Restaurant"))
                .andExpect(content().string(containsString("Burger")))
                .andExpect(content().string(not(containsString("Pasta"))));
    }

    /**
     * Characterization of current behavior: a request without a token is rejected with 403
     * (Spring's default for this configuration). AGF-S1-07 changes this to 401.
     */
    @Test
    void protectedEndpointWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/menus"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cloudflareClientsAreMocked() {
        assertThat(Mockito.mockingDetails(s3Client).isMock()).isTrue();
        assertThat(Mockito.mockingDetails(s3Presigner).isMock()).isTrue();
    }
}
