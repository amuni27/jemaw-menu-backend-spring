package com.agafari.com.location;

import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-12: the restaurant's Google Maps link at registration, on the account (business update, D-S2-12-P1: blank
 * removes, omitted keeps), in the session and on the guest menu/preview; strict validation with S1-10 field errors;
 * tenant isolation.
 */
class BusinessLocationTest extends AbstractIntegrationTest {

    private static final String LINK = "https://maps.app.goo.gl/AbC123xyz";

    @Autowired private BusinessRepository businesses;
    @Autowired private UserRepository users;

    // CR1: a US restaurant, where the link is optional (Ethiopian rules: CountryLocationRulesTest)
    private ResultActions register(String email, String locationJson) throws Exception {
        String extra = locationJson == null ? "" : "," + locationJson;
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Loc Owner","email":"%s","password":"Str0ng!Pass","businessName":"Loc Cafe",
                 "country":"US","businessPhone":"+12125550155","streetAddress":"350 5th Ave","city":"New York","state":"NY",
                 "zipcode":"10118","timeZone":"America/New_York","customSubdomain":"%s","open24_7":true%s}""".formatted(email, email.split("@")[0], extra)));
    }

    private String loginBearer(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Str0ng!Pass\"}")).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private ResultActions update(Tenant who, String businessId, String json) throws Exception {
        return mockMvc.perform(patch("/api/business/{id}", businessId).header("Authorization", data.bearer(who))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String storedLink(String businessId) {
        return businesses.findById(businessId).orElseThrow().getLocationUrl();
    }

    // AC1 (registration) + session
    @Test
    void registrationStoresANormalisedLinkAndTheSessionShowsIt() throws Exception {
        register("withlink@test.agafari.local", "\"locationUrl\":\"  https://WWW.Google.com/maps/place/Loc+Cafe  \"")
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/auth/me").header("Authorization", loginBearer("withlink@test.agafari.local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.locationUrl").value("https://www.google.com/maps/place/Loc+Cafe"));
    }

    // AC3 (CR1: United States; an Ethiopian registration needs the link)
    @Test
    void registrationWithoutALinkSucceeds() throws Exception {
        register("nolink@test.agafari.local", null).andExpect(status().isCreated());
        register("blanklink@test.agafari.local", "\"locationUrl\":\"   \"").andExpect(status().isCreated());
        mockMvc.perform(get("/api/auth/me").header("Authorization", loginBearer("blanklink@test.agafari.local")))
                .andExpect(jsonPath("$.business.locationUrl").value(nullValue()));
    }

    // AC2 (registration)
    @Test
    void anInvalidLinkAtRegistrationIs400WithAFieldErrorAndNothingIsCreated() throws Exception {
        long before = users.count();
        register("badlink@test.agafari.local", "\"locationUrl\":\"https://google.com.evil.example/maps/x\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("locationUrl"));
        assertThat(users.count()).isEqualTo(before);
    }

    // D-S2-12-P4: old clients' address fields are ignored, as before
    @Test
    void legacyAddressFieldsFromOldClientsAreIgnored() throws Exception {
        register("legacy@test.agafari.local", "\"subCity\":\"Bole\",\"woreda\":\"03\",\"kebele\":\"12\",\"houseNumber\":\"45\"")
                .andExpect(status().isCreated());
    }

    // AC1, AC4 (account) + D-S2-12-P1
    @Test
    void theOwnerSetsReplacesKeepsAndRemovesTheLink() throws Exception {
        Tenant owner = data.tenant("alpha");
        String id = owner.businessId();
        var business = businesses.findById(id).orElseThrow(); // CR1: removal is a US option (Ethiopia must keep a link)
        business.setCountry("US");
        businesses.save(business);

        update(owner, id, "{\"locationUrl\":\"" + LINK + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.locationUrl").value(LINK));
        assertThat(storedLink(id)).isEqualTo(LINK);

        update(owner, id, "{\"name\":\"Alpha Bistro\"}").andExpect(status().isOk()).andExpect(jsonPath("$.locationUrl").value(LINK));
        assertThat(storedLink(id)).isEqualTo(LINK); // omitted = unchanged

        update(owner, id, "{\"locationUrl\":\"https://goo.gl/maps/NewCode\"}").andExpect(status().isOk());
        assertThat(storedLink(id)).isEqualTo("https://goo.gl/maps/NewCode");

        update(owner, id, "{\"locationUrl\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.locationUrl").value(nullValue()));
        assertThat(storedLink(id)).isNull(); // blank = remove
    }

    // AC2 (account)
    @Test
    void anInvalidLinkOnTheAccountIs400AndTheOldLinkStays() throws Exception {
        Tenant owner = data.tenant("alpha");
        update(owner, owner.businessId(), "{\"locationUrl\":\"" + LINK + "\"}").andExpect(status().isOk());

        for (String bad : new String[]{"javascript:alert(1)", "http://maps.google.com/x", "https://www.google.com.et/maps/x",
                "https://maps.google.com/" + "a".repeat(2048)}) {
            update(owner, owner.businessId(), "{\"locationUrl\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("locationUrl"));
        }
        assertThat(storedLink(owner.businessId())).isEqualTo(LINK);
    }

    // AC9
    @Test
    void anotherRestaurantCannotChangeTheLink() throws Exception {
        Tenant owner = data.tenant("alpha");
        Tenant other = data.tenant("beta");
        update(owner, owner.businessId(), "{\"locationUrl\":\"" + LINK + "\"}").andExpect(status().isOk());

        update(other, owner.businessId(), "{\"locationUrl\":\"https://goo.gl/maps/Hijack\"}").andExpect(status().isForbidden());
        update(other, owner.businessId(), "{\"locationUrl\":\"\"}").andExpect(status().isForbidden());
        assertThat(storedLink(owner.businessId())).isEqualTo(LINK);
    }

    // AC5 (guest data) + owner preview
    @Test
    void theGuestMenuAndThePreviewCarryTheLinkOnlyWhenSaved() throws Exception {
        Tenant owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");
        data.item(data.category(menu, "Mains"), "Doro Wat");

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.business.locationUrl").value(nullValue()));

        update(owner, owner.businessId(), "{\"locationUrl\":\"" + LINK + "\"}").andExpect(status().isOk());
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.locationUrl").value(LINK));
        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.business.locationUrl").value(LINK));
    }
}
