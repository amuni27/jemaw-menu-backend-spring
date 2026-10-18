package com.agafari.com.controller;

import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.service.CurrencyPolicy;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S2-02: one currency per restaurant (ETB or USD, D6), used by owner and guest responses. */
class CurrencyTest extends AbstractIntegrationTest {

    @Autowired private BusinessRepository businesses;

    private void setCurrency(Tenant tenant, String currency) {
        var b = businesses.findById(tenant.businessId()).orElseThrow();
        b.setCurrency(currency);
        businesses.save(b);
    }

    @Test
    void ownerCanSetEtbOrUsdAndOtherValuesAreRejected() throws Exception {
        Tenant owner = data.tenant("alpha");

        mockMvc.perform(patch("/api/business/{id}", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\" usd \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));

        mockMvc.perform(patch("/api/business/{id}", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported currency. Use ETB or USD."));

        assertThat(businesses.findById(owner.businessId()).orElseThrow().getCurrency()).isEqualTo("USD");
    }

    @Test
    void otherProfileUpdatesKeepTheStoredCurrency() throws Exception {
        Tenant owner = data.tenant("alpha");
        setCurrency(owner, "GBP"); // a legacy value set outside the UI is preserved, not rewritten

        mockMvc.perform(patch("/api/business/{id}", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alpha Bistro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("GBP"));
    }

    @Test
    void newBusinessesDefaultToEtb() throws Exception {
        Tenant owner = data.tenant("alpha");
        assertThat(businesses.findById(owner.businessId()).orElseThrow().getCurrency()).isEqualTo("ETB");
    }

    @Test
    void guestMenuAndItemDetailCarryTheBusinessCurrency() throws Exception {
        Tenant owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.currency").value("ETB"))
                .andExpect(jsonPath("$.menus[0].currency").value("ETB"));

        setCurrency(owner, "USD");

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.currency").value("USD"))
                .andExpect(jsonPath("$.menus[0].currency").value("USD"));
        mockMvc.perform(get("/venue/{b}/items/{i}", owner.businessId(), item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void ownerMenusAndSessionSummaryCarryTheBusinessCurrency() throws Exception {
        Tenant owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");
        setCurrency(owner, "USD");

        mockMvc.perform(get("/api/menus/{id}", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.currency").value("USD"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.business.currency").value("USD"));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"" + TestDataFactory.PASSWORD + "\"}"))
                .andExpect(jsonPath("$.business.currency").value("USD"));
    }

    @Test
    void displayFallbackIsEtbNotUsd() {
        assertThat(CurrencyPolicy.displayCurrency(null)).isEqualTo("ETB");
        assertThat(CurrencyPolicy.displayCurrency("  ")).isEqualTo("ETB");
        assertThat(CurrencyPolicy.displayCurrency("USD")).isEqualTo("USD");
    }
}
