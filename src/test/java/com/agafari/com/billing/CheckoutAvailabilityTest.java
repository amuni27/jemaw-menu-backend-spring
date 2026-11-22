package com.agafari.com.billing;

import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.RecordingEmailVerificationMailer;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AGF BE-F0 (register §84 D2, D4): the owner's plan view says whether a paid checkout can be started (the market's billing is
 * active), and a paid checkout follows SS-2: a verified email is required only while email verification is operational.
 */
class CheckoutAvailabilityTest extends CheckoutTestBase {

    @Autowired private PlanRequestService planRequests;
    @Autowired private RecordingEmailVerificationMailer mailer;
    @Autowired private ObjectMapper mapper;
    @Autowired private com.agafari.com.service.PlanPriceService planPrices;

    @BeforeEach
    void visible() {
        planRequests.setOwnerPlansVisible(true);
        planPrices.setPricesVisible(Market.ET, true);
        planPrices.setPricesVisible(Market.US, true);
        mailer.setOperational(true);
    }

    @AfterEach
    void restore() {
        planRequests.setOwnerPlansVisible(false);
        planPrices.setPricesVisible(Market.ET, false);
        planPrices.setPricesVisible(Market.US, false);
        mailer.setOperational(true);
    }

    private JsonNode view(Tenant t) throws Exception {
        return mapper.readTree(mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString());
    }

    private void verify(Tenant t) {
        jdbc.update("update users set email_verified_at = now() where id = ?", t.owner().getId());
    }

    private int checkoutRows(Tenant t) {
        return jdbc.queryForObject("select count(*) from checkout_session where business_id = ?", Integer.class, t.businessId());
    }

    // ---- D2: checkoutAvailable ----

    @Test
    void theViewSaysCheckoutIsAvailableOnlyWhileTheRestaurantsMarketHasBillingOn() throws Exception {
        assertThat(view(owner).path("checkoutAvailable").asBoolean()).as("ET billing on (test fixture)").isTrue();
        checkouts.setBillingEnabled(Market.ET, false);
        assertThat(view(owner).path("checkoutAvailable").isBoolean()).isTrue();
        assertThat(view(owner).path("checkoutAvailable").asBoolean()).as("ET billing off").isFalse();
    }

    @Test
    void anotherMarketsBillingNeverMakesCheckoutAvailable() throws Exception {
        Tenant us = data.tenant("availability-us");
        jdbc.update("update business set country = 'US' where id = ?", us.businessId());
        checkouts.setBillingEnabled(Market.US, false);
        assertThat(view(us).path("checkoutAvailable").asBoolean(false)).as("only ET is on").isFalse();
        assertThat(view(owner).path("checkoutAvailable").asBoolean()).isTrue();
    }

    @Test
    void aRestaurantWithoutABillingMarketIsNeverAvailable() throws Exception {
        Tenant elsewhere = data.tenant("availability-elsewhere");
        jdbc.update("update business set country = 'KE' where id = ?", elsewhere.businessId());
        assertThat(view(elsewhere).path("checkoutAvailable").asBoolean(false)).as("no market: no prices, so absent (read as false)").isFalse();
    }

    @Test
    void whilePricesAreHiddenTheViewHasNoAvailabilityAtAll() throws Exception {
        planPrices.setPricesVisible(Market.ET, false);
        JsonNode v = view(owner);
        assertThat(v.has("prices")).isFalse();
        assertThat(v.has("checkoutAvailable")).as("the view is unchanged while prices are hidden").isFalse();
    }

    @Test
    void aHiddenViewHasNoAvailability() throws Exception {
        planRequests.setOwnerPlansVisible(false);
        JsonNode v = view(owner);
        assertThat(v.path("visible").asBoolean()).isFalse();
        assertThat(v.has("checkoutAvailable")).isFalse();
    }

    // ---- D4: SS-2 email verification for a paid checkout ----

    @Test
    void anUnverifiedOwnerCantStartAPaidCheckoutWhileVerificationIsOperational() throws Exception {
        var r = perform(owner, "key-unverified-1", price).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(r.getContentAsString()).path("code").asString()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(checkoutRows(owner)).as("nothing reserved").isZero();
        assertThat(fakeEt.createCalls()).as("the provider is never asked").isZero();
    }

    @Test
    void aVerifiedOwnerStartsAPaidCheckout() throws Exception {
        verify(owner);
        assertThat(httpStatus(owner, "key-verified-1", price)).isEqualTo(201);
        assertThat(checkoutRows(owner)).isEqualTo(1);
    }

    @Test
    void withoutOperationalVerificationNoVerifiedEmailIsNeeded() throws Exception {
        mailer.setOperational(false); // no email provider yet (production before D5)
        assertThat(httpStatus(owner, "key-not-operational-1", price)).isEqualTo(201);
    }
}
