package com.agafari.com.location;

import com.agafari.com.jpa.entity.Business;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-12 CR1: country-specific location rules (D-CR1-1…8). Ethiopia: Google Maps link required, street/city/
 * region/postal code not collected (stored empty). United States: street, city, state and ZIP required, link optional,
 * USD by default. Only ET and US; a missing country means Ethiopia. Business phone must match the country. The
 * account update follows the restaurant's stored country, which can't be changed.
 */
class CountryLocationRulesTest extends AbstractIntegrationTest {

    private static final String LINK = "https://maps.app.goo.gl/AbC123xyz";
    private static final String US_ADDRESS = "\"streetAddress\":\"350 5th Ave\",\"city\":\"New York\",\"state\":\"ny\",\"zipcode\":\"10118\"";

    @Autowired private BusinessRepository businesses;
    @Autowired private UserRepository users;

    private ResultActions register(String email, String phone, String fields) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Country Owner","email":"%s","password":"Str0ng!Pass","businessName":"Country Cafe",
                 "businessPhone":"%s","customSubdomain":"%s","open24_7":true%s}"""
                .formatted(email, phone, email.split("@")[0], fields.isEmpty() ? "" : "," + fields)));
    }

    private ResultActions ethiopian(String email, String fields) throws Exception {
        return register(email, "+251911000901", fields);
    }

    private ResultActions american(String email, String fields) throws Exception {
        return register(email, "+12125550100", fields);
    }

    private Business stored(String email) {
        String ownerId = users.findByEmail(email).orElseThrow().getId();
        return businesses.findAll().stream().filter(b -> b.getOwner().getId().equals(ownerId)).findFirst().orElseThrow();
    }

    private String loginBearer(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Str0ng!Pass\"}")).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private ResultActions update(Tenant who, String json) throws Exception {
        return mockMvc.perform(patch("/api/business/{id}", who.businessId()).header("Authorization", data.bearer(who))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static ResultActions fieldError(ResultActions r, String field) throws Exception {
        return r.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }

    // ---- Ethiopia -------------------------------------------------------------------------------------------------

    @Test
    void ethiopiaRegistersWithOnlyALinkAndStoresNoAddress() throws Exception {
        ethiopian("et1@test.agafari.local", "\"country\":\"ET\",\"locationUrl\":\"" + LINK + "\"")
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/auth/me").header("Authorization", loginBearer("et1@test.agafari.local")))
                .andExpect(jsonPath("$.business.country").value("ET"));
        Business b = stored("et1@test.agafari.local");
        assertThat(b.getCountry()).isEqualTo("ET");
        assertThat(b.getLocationUrl()).isEqualTo(LINK);
        assertThat(b.getCurrency()).isEqualTo("ETB");
        assertThat(new String[]{b.getStreetAddress(), b.getCity(), b.getState(), b.getZipcode()}).containsOnly("");
    }

    @Test
    void ethiopiaWithoutALinkIsRejectedAndNothingIsCreated() throws Exception {
        long before = users.count();
        fieldError(ethiopian("et2@test.agafari.local", "\"country\":\"ET\""), "locationUrl");
        fieldError(ethiopian("et3@test.agafari.local", "\"country\":\"ET\",\"locationUrl\":\"  \""), "locationUrl");
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void ethiopiaIgnoresAddressFieldsSentByAClient() throws Exception {
        ethiopian("et4@test.agafari.local", "\"country\":\"ET\",\"locationUrl\":\"" + LINK + "\"," + US_ADDRESS)
                .andExpect(status().isCreated());
        Business b = stored("et4@test.agafari.local");
        assertThat(new String[]{b.getStreetAddress(), b.getCity(), b.getState(), b.getZipcode()}).containsOnly("");
    }

    @Test
    void aMissingCountryMeansEthiopia() throws Exception {
        fieldError(ethiopian("et5@test.agafari.local", "\"streetAddress\":\"1 St\",\"city\":\"Addis Ababa\",\"state\":\"AA\""), "locationUrl");
        ethiopian("et6@test.agafari.local", "\"locationUrl\":\"" + LINK + "\"").andExpect(status().isCreated());
        assertThat(stored("et6@test.agafari.local").getCountry()).isEqualTo("ET");
    }

    @Test
    void ethiopiaNeedsAnEthiopianBusinessPhone() throws Exception {
        fieldError(register("et7@test.agafari.local", "+12125550100", "\"country\":\"ET\",\"locationUrl\":\"" + LINK + "\""), "businessPhone");
    }

    // ---- United States ----------------------------------------------------------------------------------------------

    @Test
    void theUnitedStatesRegistersWithTheFullAddressAndNoLink() throws Exception {
        american("us1@test.agafari.local", "\"country\":\"US\"," + US_ADDRESS)
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/auth/me").header("Authorization", loginBearer("us1@test.agafari.local")))
                .andExpect(jsonPath("$.business.country").value("US"))
                .andExpect(jsonPath("$.business.locationUrl").value(nullValue()));
        Business b = stored("us1@test.agafari.local");
        assertThat(b.getCountry()).isEqualTo("US");
        assertThat(b.getStreetAddress()).isEqualTo("350 5th Ave");
        assertThat(b.getCity()).isEqualTo("New York");
        assertThat(b.getState()).isEqualTo("NY"); // stored as the 2-letter code (D-CR1-6)
        assertThat(b.getZipcode()).isEqualTo("10118");
        assertThat(b.getCurrency()).isEqualTo("USD"); // D-CR1-8
    }

    @Test
    void theUnitedStatesAcceptsAnOptionalLinkAndZipPlusFour() throws Exception {
        american("us2@test.agafari.local", "\"country\":\"us\",\"locationUrl\":\"" + LINK + "\","
                + US_ADDRESS.replace("10118", "10118-0110")).andExpect(status().isCreated());
        Business b = stored("us2@test.agafari.local");
        assertThat(b.getLocationUrl()).isEqualTo(LINK);
        assertThat(b.getZipcode()).isEqualTo("10118-0110");
    }

    @Test
    void theUnitedStatesRejectsEachMissingAddressField() throws Exception {
        long before = users.count();
        String[][] cases = {
                {"streetAddress", "\"city\":\"New York\",\"state\":\"NY\",\"zipcode\":\"10118\""},
                {"city", "\"streetAddress\":\"350 5th Ave\",\"city\":\" \",\"state\":\"NY\",\"zipcode\":\"10118\""},
                {"state", "\"streetAddress\":\"350 5th Ave\",\"city\":\"New York\",\"zipcode\":\"10118\""},
                {"zipcode", "\"streetAddress\":\"350 5th Ave\",\"city\":\"New York\",\"state\":\"NY\",\"zipcode\":\"\""},
        };
        int n = 0;
        for (String[] c : cases) {
            fieldError(american("usmiss" + (n++) + "@test.agafari.local", "\"country\":\"US\"," + c[1]), c[0]);
        }
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void theUnitedStatesRejectsAnUnknownStateAndABadZip() throws Exception {
        fieldError(american("usbad1@test.agafari.local", "\"country\":\"US\"," + US_ADDRESS.replace("\"ny\"", "\"Addis Ababa\"")), "state");
        fieldError(american("usbad2@test.agafari.local", "\"country\":\"US\"," + US_ADDRESS.replace("10118", "1011")), "zipcode");
        fieldError(american("usbad3@test.agafari.local", "\"country\":\"US\"," + US_ADDRESS.replace("10118", "ABCDE")), "zipcode");
    }

    @Test
    void theUnitedStatesNeedsAUsBusinessPhone() throws Exception {
        fieldError(register("usphone@test.agafari.local", "+251911000902", "\"country\":\"US\"," + US_ADDRESS), "businessPhone");
    }

    @Test
    void otherCountriesAreRejected() throws Exception {
        fieldError(ethiopian("kenya@test.agafari.local", "\"country\":\"KE\",\"locationUrl\":\"" + LINK + "\""), "country");
    }

    // ---- Account editing follows the stored country --------------------------------------------------------------

    @Test
    void anEthiopianRestaurantCanReplaceButNotRemoveItsLinkAndIgnoresAddressEdits() throws Exception {
        Tenant owner = data.tenant("alpha"); // existing restaurant: country ET, no link (D-CR1-5)
        update(owner, "{\"name\":\"Alpha Bistro\"}").andExpect(status().isOk()); // not blocked without a link
        update(owner, "{\"locationUrl\":\"\"}").andExpect(status().isOk());       // nothing to remove: no-op

        update(owner, "{\"locationUrl\":\"" + LINK + "\"}").andExpect(status().isOk());
        fieldError(update(owner, "{\"locationUrl\":\"\"}"), "locationUrl");
        assertThat(businesses.findById(owner.businessId()).orElseThrow().getLocationUrl()).isEqualTo(LINK);

        update(owner, "{\"locationUrl\":\"https://goo.gl/maps/NewCode\"}").andExpect(status().isOk());
        update(owner, "{\"streetAddress\":\"Elsewhere 9\",\"city\":\"Hawassa\"}").andExpect(status().isOk());
        Business b = businesses.findById(owner.businessId()).orElseThrow();
        assertThat(b.getLocationUrl()).isEqualTo("https://goo.gl/maps/NewCode");
        assertThat(b.getStreetAddress()).isEqualTo("1 Test Street"); // stored data untouched
        assertThat(b.getCity()).isEqualTo("Addis Ababa");
    }

    @Test
    void anAmericanRestaurantKeepsItsAddressValidAndMayRemoveItsLink() throws Exception {
        Tenant owner = data.tenant("alpha");
        Business b = businesses.findById(owner.businessId()).orElseThrow();
        b.setCountry("US");
        b.setBusinessPhone("+12125550100");
        b.setState("NY");
        b.setZipcode("10118");
        b.setLocationUrl(LINK);
        businesses.save(b);

        fieldError(update(owner, "{\"city\":\"  \"}"), "city");
        fieldError(update(owner, "{\"zipcode\":\"abc\"}"), "zipcode");
        fieldError(update(owner, "{\"state\":\"Oromia\"}"), "state");
        fieldError(update(owner, "{\"businessPhone\":\"+251911000903\"}"), "businessPhone");

        update(owner, "{\"state\":\"ca\",\"city\":\"Los Angeles\",\"zipcode\":\"90001-1234\",\"locationUrl\":\"\"}").andExpect(status().isOk());
        b = businesses.findById(owner.businessId()).orElseThrow();
        assertThat(b.getState()).isEqualTo("CA");
        assertThat(b.getCity()).isEqualTo("Los Angeles");
        assertThat(b.getZipcode()).isEqualTo("90001-1234");
        assertThat(b.getLocationUrl()).isNull();
    }

    @Test
    void theCountryCannotBeChangedOnTheAccount() throws Exception {
        Tenant owner = data.tenant("alpha");
        update(owner, "{\"country\":\"US\"}").andExpect(status().isOk());
        assertThat(businesses.findById(owner.businessId()).orElseThrow().getCountry()).isEqualTo("ET");
    }

    @Test
    void theGuestMenuAndTheAccountCarryTheCountry() throws Exception {
        Tenant owner = data.tenant("alpha");
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(jsonPath("$.business.country").value("ET"));
        update(owner, "{\"name\":\"Alpha\"}").andExpect(jsonPath("$.country").value("ET"));
    }
}
