package com.agafari.com.timezone;

import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.MenuRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.service.impl.PublicMenuServiceImpl;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-26: the restaurant's time zone. Ethiopia is always Africa/Addis_Ababa; US restaurants choose one of the
 * seven US zones (required at registration, editable later); responses carry the stored zone; the guest menu order
 * uses the restaurant's local time (D-S2-26-6), independent of the server's zone.
 */
class BusinessTimeZoneTest extends AbstractIntegrationTest {

    private static final String LINK = "https://maps.app.goo.gl/AbC123xyz";
    private static final String US = "\"country\":\"US\",\"streetAddress\":\"350 5th Ave\",\"city\":\"New York\",\"state\":\"NY\",\"zipcode\":\"10118\"";

    @Autowired private BusinessRepository businesses;
    @Autowired private UserRepository users;
    @Autowired private MenuRepository menus;
    @Autowired private PublicMenuServiceImpl publicMenus;

    @AfterEach
    void systemClock() {
        publicMenus.setClock(Clock.systemUTC());
    }

    private ResultActions register(String email, String phone, String fields) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Zone Owner","email":"%s","password":"Str0ng!Pass","businessName":"Zone Cafe",
                 "businessPhone":"%s","customSubdomain":"%s","open24_7":true,%s}"""
                .formatted(email, phone, email.split("@")[0], fields)));
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

    private Business american(Tenant tenant, String zone) {
        Business b = businesses.findById(tenant.businessId()).orElseThrow();
        b.setCountry("US");
        b.setBusinessPhone("+12125550100");
        b.setState("NY");
        b.setZipcode("10118");
        b.setTimeZone(zone);
        return businesses.save(b);
    }

    // ---- registration ----------------------------------------------------------------------------------------------

    @Test
    void ethiopianRestaurantsAreAlwaysOnAddisAbabaTime() throws Exception {
        register("tzet@test.agafari.local", "+251911000911", "\"country\":\"ET\",\"locationUrl\":\"" + LINK + "\",\"timeZone\":\"America/New_York\"")
                .andExpect(status().isCreated());
        assertThat(stored("tzet@test.agafari.local").getTimeZone()).isEqualTo("Africa/Addis_Ababa");
    }

    @Test
    void usRestaurantsStoreTheirChosenZone() throws Exception {
        register("tzus@test.agafari.local", "+12125550111", US + ",\"timeZone\":\"America/Chicago\"").andExpect(status().isCreated());
        assertThat(stored("tzus@test.agafari.local").getTimeZone()).isEqualTo("America/Chicago");
        mockMvc.perform(get("/api/auth/me").header("Authorization", loginBearer("tzus@test.agafari.local")))
                .andExpect(jsonPath("$.business.timeZone").value("America/Chicago"));
    }

    @Test
    void usRestaurantsNeedASupportedZone() throws Exception {
        long before = users.count();
        for (String[] c : new String[][]{{"tzmiss", ""}, {"tzlon", ",\"timeZone\":\"Europe/London\""}, {"tzaddis", ",\"timeZone\":\"Africa/Addis_Ababa\""}}) {
            register(c[0] + "@test.agafari.local", "+12125550112", US + c[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("timeZone"));
        }
        assertThat(users.count()).isEqualTo(before);
    }

    // ---- account editing ------------------------------------------------------------------------------------------

    @Test
    void aUsOwnerCanChangeTheZoneAndAnEthiopianOwnerCannot() throws Exception {
        Tenant us = data.tenant("alpha");
        american(us, "America/New_York");
        update(us, "{\"timeZone\":\"America/Phoenix\"}").andExpect(status().isOk()).andExpect(jsonPath("$.timeZone").value("America/Phoenix"));
        update(us, "{\"timeZone\":\"Asia/Tokyo\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timeZone"));
        update(us, "{\"name\":\"Alpha NYC\"}").andExpect(status().isOk());
        assertThat(businesses.findById(us.businessId()).orElseThrow().getTimeZone()).isEqualTo("America/Phoenix");

        Tenant et = data.tenant("beta");
        update(et, "{\"timeZone\":\"America/Chicago\"}").andExpect(status().isOk()).andExpect(jsonPath("$.timeZone").value("Africa/Addis_Ababa"));
        assertThat(businesses.findById(et.businessId()).orElseThrow().getTimeZone()).isEqualTo("Africa/Addis_Ababa");
    }

    // ---- guest data ------------------------------------------------------------------------------------------------

    @Test
    void theGuestMenuAndThePreviewCarryTheStoredZone() throws Exception {
        Tenant us = data.tenant("alpha");
        american(us, "America/Denver");
        Menu menu = data.menu(us, "Lunch");
        data.item(data.category(menu, "Mains"), "Burger");
        mockMvc.perform(get("/venue/{id}/menu", us.businessId())).andExpect(jsonPath("$.business.timeZone").value("America/Denver"));
        mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(us)))
                .andExpect(jsonPath("$.business.timeZone").value("America/Denver"));
        Tenant et = data.tenant("beta");
        mockMvc.perform(get("/venue/{id}/menu", et.businessId())).andExpect(jsonPath("$.business.timeZone").value("Africa/Addis_Ababa"));
    }

    // ---- menu order in the restaurant's local time (D-S2-26-6) -----------------------------------------------------

    private List<String> guestMenuOrder(Tenant tenant, String instant) throws Exception {
        publicMenus.setClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        String body = mockMvc.perform(get("/venue/{id}/menu", tenant.businessId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.menus[*].name");
    }

    private void breakfastAndLunch(Tenant tenant) {
        for (String type : new String[]{"Lunch", "Breakfast"}) { // seeded: Breakfast 07:00, Lunch 12:30 (Addis wall clock)
            Menu menu = data.menu(tenant, type + " Menu");
            menu.setMenuType(data.menuType(type));
            menus.save(menu);
            data.item(data.category(menu, "Dishes"), type + " dish");
        }
    }

    @Test
    void aUsRestaurantOrdersItsMenusByItsOwnLocalTime() throws Exception {
        Tenant us = data.tenant("alpha");
        american(us, "America/New_York");
        breakfastAndLunch(us);
        // 13:00 UTC in January = 08:00 in New York (Breakfast closest), but already 16:00 in Addis Ababa
        assertThat(guestMenuOrder(us, "2026-01-15T13:00:00Z")).containsExactly("Breakfast Menu", "Lunch Menu");
        // 16:30 UTC in July = 12:30 in New York (daylight saving): Lunch first
        assertThat(guestMenuOrder(us, "2026-07-15T16:30:00Z")).containsExactly("Lunch Menu", "Breakfast Menu");
    }

    @Test
    void anEthiopianRestaurantKeepsAddisMealTimesWhateverTheServerZone() throws Exception {
        Tenant et = data.tenant("beta");
        breakfastAndLunch(et);
        TimeZone server = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(guestMenuOrder(et, "2026-01-15T05:00:00Z")).as(zone).containsExactly("Breakfast Menu", "Lunch Menu"); // 08:00 Addis
                assertThat(guestMenuOrder(et, "2026-01-15T09:30:00Z")).as(zone).containsExactly("Lunch Menu", "Breakfast Menu"); // 12:30 Addis
            }
        } finally {
            TimeZone.setDefault(server);
        }
    }
}
