package com.agafari.com.seed;

import com.agafari.com.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-09: a fresh database (V1 + V2) has the nine PO-1 menu types with fixed IDs and documented times of day
 * (D-S1-09-1, -2), and a newly registered owner can create a menu with them.
 */
class MenuTypeSeedTest extends AbstractIntegrationTest {

    static final List<String> PO1 = List.of("Breakfast", "Brunch", "Lunch", "Dinner", "All Day", "Drinks", "Desserts", "Specials", "Kids");

    @Autowired private JdbcTemplate jdbc;

    // AC1
    @Test
    void aFreshDatabaseListsExactlyTheNineApprovedTypes() throws Exception {
        var owner = data.tenant("alpha");
        mockMvc.perform(get("/api/menus/type/all").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder(PO1.toArray())));
    }

    // D-S1-09-1, D-S1-09-2
    @Test
    void eachTypeHasItsFixedIdAndAddisAbabaTimeOfDay() {
        Map<String, String> expected = Map.of(
                "mt-breakfast", "Breakfast@07:00", "mt-brunch", "Brunch@10:00", "mt-kids", "Kids@11:30",
                "mt-lunch", "Lunch@12:30", "mt-all-day", "All Day@13:00", "mt-specials", "Specials@14:00",
                "mt-desserts", "Desserts@15:30", "mt-drinks", "Drinks@17:00", "mt-dinner", "Dinner@19:00");
        List<Map<String, Object>> rows = jdbc.queryForList("select id, name, \"updatedAt\" as updated from menu_type order by id");
        assertThat(rows).hasSize(9);
        for (Map<String, Object> row : rows) {
            Instant updated = ((java.sql.Timestamp) row.get("updated")).toInstant();
            String addis = updated.atOffset(ZoneOffset.ofHours(3)).toLocalTime().toString();
            assertThat(row.get("name") + "@" + addis).isEqualTo(expected.get((String) row.get("id")));
        }
    }

    // AC5
    @Test
    void aNewlyRegisteredOwnerSeesTheTypesAndCanCreateAMenu() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"New Owner","email":"new.owner@test.agafari.local","password":"Str0ng!Pass","businessName":"New Cafe",
                         "businessPhone":"+251911000777","streetAddress":"1 Test St","city":"Addis Ababa","state":"Addis Ababa",
                         "zipcode":"","customSubdomain":"new-cafe","open24_7":true}"""))
                .andExpect(status().isCreated());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.owner@test.agafari.local\",\"password\":\"Str0ng!Pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.token");

        String types = mockMvc.perform(get("/api/menus/type/all").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(9))).andReturn().getResponse().getContentAsString();
        String brunch = ((List<String>) JsonPath.read(types, "$[?(@.name == 'Brunch')].id")).get(0);

        mockMvc.perform(post("/api/menus").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Weekend Brunch\",\"menuTypeId\":\"" + brunch + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.menuTypeName").value("Brunch"));
    }

    // D-S1-09-5: the seed is reference data and survives the per-test cleanup
    @Test
    void theSeedSurvivesTestCleanupWhileTestCreatedTypesDoNot() {
        data.menuType("E2E Temporary Type");
        data.deleteAll();
        assertThat(jdbc.queryForObject("select count(*) from menu_type", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from menu_type where name = 'E2E Temporary Type'", Integer.class)).isZero();
    }
}
