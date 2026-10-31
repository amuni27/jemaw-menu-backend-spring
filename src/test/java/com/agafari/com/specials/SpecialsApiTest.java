package com.agafari.com.specials;

import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.SpecialRepository;
import com.agafari.com.service.impl.SpecialServiceImpl;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-25 (checkpoint A): owner specials API. Create, list with status (restaurant time), edit, pause/resume and
 * delete; validation with S1-10 field errors; one special per dish; tenant isolation (another owner's dish or special
 * → 404); deleted with the dish or menu; the database refuses a special whose restaurant differs from its dish's;
 * checkpoint A sends no special data to guests.
 */
class SpecialsApiTest extends AbstractIntegrationTest {

    @Autowired private SpecialRepository specials;
    @Autowired private SpecialServiceImpl specialService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.agafari.com.jpa.repository.MenuItemRepository items;

    private Tenant owner;
    private Menu menu;
    private MenuItem doro;  // regular price 100.00

    @BeforeEach
    void dish() {
        owner = data.tenant("alpha");
        menu = data.menu(owner, "Lunch");
        doro = data.item(data.category(menu, "Mains"), "Doro Wat");
        doro.setPrice(new java.math.BigDecimal("100.00"));
        doro = items.save(doro);
        // Wednesday 2026-11-04 12:00 in Addis Ababa
        specialService.setClock(Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void systemClock() {
        specialService.setClock(Clock.systemUTC());
    }

    private ResultActions send(MockHttpServletRequestBuilder req, Tenant who, String json) throws Exception {
        return mockMvc.perform(req.header("Authorization", data.bearer(who)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions create(Tenant who, String fields) throws Exception {
        return send(post("/api/specials"), who, "{\"menuItemId\":\"" + doro.getId() + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}");
    }

    private String createdId(String fields) throws Exception {
        return JsonPath.read(create(owner, fields).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static ResultActions fieldError(ResultActions r, String field) throws Exception {
        return r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }

    // ---- create and list ---------------------------------------------------------------------------------------

    @Test
    void anOwnerCreatesASpecialAndSeesItListedWithItsDish() throws Exception {
        create(owner, "\"specialPrice\":79.5,\"note\":\" Happy hour \",\"noteAm\":\"ደስታ ሰዓት\",\"daysOfWeek\":[\"FRIDAY\",\"MONDAY\"],"
                + "\"startTime\":\"16:00\",\"endTime\":\"18:00\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.menuItemId").value(doro.getId()))
                .andExpect(jsonPath("$.itemName").value("Doro Wat"))
                .andExpect(jsonPath("$.menuName").value("Lunch"))
                .andExpect(jsonPath("$.regularPrice").value(100.0))
                .andExpect(jsonPath("$.specialPrice").value(79.5))
                .andExpect(jsonPath("$.note").value("Happy hour"))
                .andExpect(jsonPath("$.noteAm").value("ደስታ ሰዓት"))
                .andExpect(jsonPath("$.daysOfWeek[0]").value("MONDAY")) // stored in week order
                .andExpect(jsonPath("$.status").value("SCHEDULED")) // Wednesday noon: not a happy-hour day
                .andExpect(jsonPath("$.paused").value(false));

        mockMvc.perform(get("/api/specials").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemName").value("Doro Wat"));
    }

    @Test
    void theStatusFollowsTheRestaurantsLocalTime() throws Exception {
        assertThat((String) JsonPath.read(create(owner, "\"specialPrice\":80").andReturn().getResponse().getContentAsString(), "$.status")).isEqualTo("ACTIVE");
        specials.deleteAll();
        create(owner, "\"specialPrice\":80,\"startsOn\":\"2026-11-05\"").andExpect(jsonPath("$.status").value("SCHEDULED"));
        specials.deleteAll();
        create(owner, "\"specialPrice\":80,\"endsOn\":\"2026-11-03\"").andExpect(jsonPath("$.status").value("ENDED"));
        specials.deleteAll();
        create(owner, "\"specialPrice\":80,\"paused\":true").andExpect(jsonPath("$.status").value("PAUSED"));
    }

    // ---- validation -----------------------------------------------------------------------------------------------

    @Test
    void invalidSpecialsAreRejectedWithFieldErrorsAndNothingIsSaved() throws Exception {
        fieldError(create(owner, ""), "specialPrice");
        fieldError(create(owner, "\"specialPrice\":100"), "specialPrice");       // not below the regular price
        fieldError(create(owner, "\"specialPrice\":120"), "specialPrice");
        fieldError(create(owner, "\"specialPrice\":-1"), "specialPrice");
        fieldError(create(owner, "\"specialPrice\":79.999"), "specialPrice");    // more than 2 decimals
        fieldError(create(owner, "\"specialPrice\":80,\"startsOn\":\"2026-11-10\",\"endsOn\":\"2026-11-09\""), "endsOn");
        fieldError(create(owner, "\"specialPrice\":80,\"startTime\":\"18:00\",\"endTime\":\"16:00\""), "endTime");
        fieldError(create(owner, "\"specialPrice\":80,\"startTime\":\"16:00\""), "endTime");
        fieldError(create(owner, "\"specialPrice\":80,\"endTime\":\"16:00\""), "startTime");
        fieldError(create(owner, "\"specialPrice\":80,\"startTime\":\"25:00\",\"endTime\":\"26:00\""), "startTime");
        fieldError(create(owner, "\"specialPrice\":80,\"daysOfWeek\":[\"FUNDAY\"]"), "daysOfWeek");
        fieldError(create(owner, "\"specialPrice\":80,\"note\":\"" + "x".repeat(256) + "\""), "note");
        fieldError(create(owner, "\"specialPrice\":80,\"noteAm\":\"" + "ሀ".repeat(256) + "\""), "noteAm");
        fieldError(send(post("/api/specials"), owner, "{\"specialPrice\":80}"), "menuItemId");
        assertThat(specials.count()).isZero();
    }

    @Test
    void aDishHasAtMostOneSpecial() throws Exception {
        createdId("\"specialPrice\":80");
        fieldError(create(owner, "\"specialPrice\":70"), "menuItemId");
        assertThat(specials.count()).isEqualTo(1);
    }

    // ---- edit, pause, delete --------------------------------------------------------------------------------------

    @Test
    void theOwnerEditsPausesResumesAndDeletesASpecial() throws Exception {
        String id = createdId("\"specialPrice\":80,\"note\":\"Lunch deal\"");
        send(patch("/api/specials/{id}", id), owner, "{\"specialPrice\":75,\"startTime\":\"11:00\",\"endTime\":\"14:00\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialPrice").value(75.0))
                .andExpect(jsonPath("$.note").value("Lunch deal")) // omitted = unchanged
                .andExpect(jsonPath("$.status").value("ACTIVE"));  // 12:00 is within 11:00–14:00
        send(patch("/api/specials/{id}", id), owner, "{\"paused\":true}").andExpect(jsonPath("$.status").value("PAUSED"));
        send(patch("/api/specials/{id}", id), owner, "{\"paused\":false}").andExpect(jsonPath("$.status").value("ACTIVE"));
        // S2-30 style: "" clears the note; clearTimeWindow removes the window (null/omitted = unchanged)
        send(patch("/api/specials/{id}", id), owner, "{\"note\":\"\",\"clearTimeWindow\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(specials.findById(id).orElseThrow().getNote()).isNull();
        assertThat(specials.findById(id).orElseThrow().getStartTime()).isNull();
        fieldError(send(patch("/api/specials/{id}", id), owner, "{\"specialPrice\":150}"), "specialPrice");

        mockMvc.perform(delete("/api/specials/{id}", id).header("Authorization", data.bearer(owner))).andExpect(status().isNoContent());
        assertThat(specials.count()).isZero();
    }

    // ---- tenant isolation -----------------------------------------------------------------------------------------

    @Test
    void anotherRestaurantCanNeitherUseTheDishNorSeeOrChangeTheSpecial() throws Exception {
        Tenant other = data.tenant("beta");
        create(other, "\"specialPrice\":80").andExpect(status().isNotFound()); // someone else's dish
        String id = createdId("\"specialPrice\":80");

        mockMvc.perform(get("/api/specials").header("Authorization", data.bearer(other))).andExpect(jsonPath("$", hasSize(0)));
        send(patch("/api/specials/{id}", id), other, "{\"paused\":true}").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/specials/{id}", id).header("Authorization", data.bearer(other))).andExpect(status().isNotFound());
        assertThat(specials.findById(id).orElseThrow().isPaused()).isFalse();
    }

    @Test
    void theDatabaseRefusesASpecialForAnotherRestaurantsDish() {
        Tenant other = data.tenant("beta");
        assertThatThrownBy(() -> jdbc.update("insert into special (id, business_id, menu_item_id, special_price, days_of_week, is_paused, "
                + "\"createdAt\", \"updatedAt\") values ('s-x', ?, ?, 1, '[]'::jsonb, false, now(), now())", other.businessId(), doro.getId()))
                .hasMessageContaining("special_business_mismatch");
        assertThat(specials.count()).isZero();
    }

    // ---- lifecycle with the dish ----------------------------------------------------------------------------------

    @Test
    void deletingTheDishOrItsMenuDeletesTheSpecial() throws Exception {
        createdId("\"specialPrice\":80");
        mockMvc.perform(delete("/api/items/{id}", doro.getId()).header("Authorization", data.bearer(owner))).andExpect(status().is2xxSuccessful());
        assertThat(specials.count()).isZero();

        MenuItem tibs = data.item(data.category(menu, "Grill"), "Tibs");
        send(post("/api/specials"), owner, "{\"menuItemId\":\"" + tibs.getId() + "\",\"specialPrice\":5}").andExpect(status().isCreated()); // Tibs costs 10.00
        mockMvc.perform(delete("/api/menus/{id}", menu.getId()).header("Authorization", data.bearer(owner))).andExpect(status().is2xxSuccessful());
        assertThat(specials.count()).isZero();
    }

    // ---- guests: nothing in checkpoint A --------------------------------------------------------------------------

    @Test
    void checkpointASendsNoSpecialDataToGuests() throws Exception {
        createdId("\"specialPrice\":80,\"note\":\"Secret plan\"");
        for (String body : new String[]{
                mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/venue/{id}/items/{item}", owner.businessId(), doro.getId())).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/menus/{id}/preview", menu.getId()).header("Authorization", data.bearer(owner))).andReturn().getResponse().getContentAsString()}) {
            assertThat(body).contains("Doro Wat").doesNotContain("special").doesNotContain("Secret plan");
        }
    }
}
