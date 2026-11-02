package com.agafari.com.ads;

import com.agafari.com.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static com.agafari.com.ads.AdTestSupport.FIELD;
import static com.agafari.com.ads.AdTestSupport.body;
import static com.agafari.com.ads.AdTestSupport.create;
import static com.agafari.com.ads.AdTestSupport.createId;
import static com.agafari.com.ads.AdTestSupport.update;
import static com.agafari.com.ads.AdTestSupport.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M7 D-M7-1 Option A (AC15): exact date and time in an explicitly chosen Super Admin time zone, stored as UTC
 * instants. Start required, end optional and after the start. A nonexistent local time is rejected; an ambiguous one needs
 * an explicit valid offset. Reading back and saving unchanged preserves the exact instant.
 */
class AdScheduleTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = data.adminBearer(data.admin("ads-schedule"));
    }

    private String ad(String start, String end, String zone) {
        return body("Schedule", "Text", "https://example.test", start, end, zone);
    }

    private Instant stored(String id, String column) {
        Timestamp ts = jdbc.queryForObject("select " + column + " from agafari_ad where id = ?", Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    @Test
    void ac15LocalTimesInTheChosenZoneAreStoredAsExactUtcInstants() throws Exception {
        String[][] cases = {
                {"UTC", "2026-12-01T09:00", "2026-12-01T09:00:00Z", "+00:00"},
                {"Africa/Addis_Ababa", "2026-12-01T09:00", "2026-12-01T06:00:00Z", "+03:00"},
                {"America/Chicago", "2026-12-01T09:00", "2026-12-01T15:00:00Z", "-06:00"},
                {"America/Chicago", "2027-07-01T09:00", "2027-07-01T14:00:00Z", "-05:00"},
        };
        for (String[] c : cases) {
            String id = createId(mockMvc, bearer, ad(c[1], null, c[0]));
            assertThat(stored(id, "starts_at")).as(c[0] + " " + c[1]).isEqualTo(Instant.parse(c[2]));
            mockMvc.perform(get("/api/admin/ads").header("Authorization", bearer))
                    .andExpect(jsonPath("$[?(@.id == '" + id + "')].startLocal").value(hasItem(c[1])))
                    .andExpect(jsonPath("$[?(@.id == '" + id + "')].startOffset").value(hasItem(c[3])))
                    .andExpect(jsonPath("$[?(@.id == '" + id + "')].scheduleTimeZone").value(hasItem(c[0])));
        }
    }

    @Test
    void ac15ANonexistentLocalTimeIsRejected() throws Exception {
        create(mockMvc, bearer, ad("2027-03-14T02:30", null, "America/Chicago")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startLocal"))
                        .value(hasItem("This time doesn't exist in America/Chicago (clocks move forward); choose another time")));
        create(mockMvc, bearer, ad("2027-03-01T09:00", "2027-03-14T02:15", "America/Chicago")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("endLocal")).exists());
    }

    @Test
    void ac15AnAmbiguousLocalTimeNeedsAnExplicitValidOffset() throws Exception {
        create(mockMvc, bearer, ad("2026-11-01T01:30", null, "America/Chicago")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startLocal"))
                        .value(hasItem("This time occurs twice in America/Chicago; choose the offset (-05:00 or -06:00)")));
        String early = createId(mockMvc, bearer, with(ad("2026-11-01T01:30", null, "America/Chicago"), "startOffset", "-05:00"));
        String late = createId(mockMvc, bearer, with(ad("2026-11-01T01:30", null, "America/Chicago"), "startOffset", "-06:00"));
        assertThat(stored(early, "starts_at")).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
        assertThat(stored(late, "starts_at")).isEqualTo(Instant.parse("2026-11-01T07:30:00Z"));
        create(mockMvc, bearer, with(ad("2026-11-01T01:30", null, "America/Chicago"), "startOffset", "-07:00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startOffset")).value(hasItem("This offset isn't valid for this time in America/Chicago")));
    }

    @Test
    void ac15MalformedValuesUnknownZonesAndAnEndNotAfterTheStartAreRejected() throws Exception {
        for (String bad : new String[] {"2026-12-01", "2026-12-01 09:00", "2026-12-01T09:00:00Z", "2026-02-30T09:00", "tomorrow"}) {
            create(mockMvc, bearer, ad(bad, null, "UTC")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath(FIELD.formatted("startLocal")).value(hasItem("Use a date and time (YYYY-MM-DDTHH:mm)")));
        }
        create(mockMvc, bearer, ad("2026-12-01T09:00", null, "Mars/Olympus")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("timeZone")).value(hasItem("Choose a time zone")));
        create(mockMvc, bearer, ad("2026-12-01T09:00", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("timeZone")).exists());
        create(mockMvc, bearer, ad("2026-12-01T09:00", "2026-12-01T08:59", "UTC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("endLocal")).value(hasItem("The end must be after the start")));
        // compared as instants in the chosen zone
        create(mockMvc, bearer, ad("2026-12-01T09:00", "2026-12-01T08:00", "America/Chicago")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from agafari_ad", Integer.class)).isZero();
    }

    @Test
    void ac15SavingTheReadBackScheduleUnchangedPreservesTheExactInstantsAndAnotherZoneKeepsThem() throws Exception {
        String id = createId(mockMvc, bearer, with(ad("2026-11-01T01:30", "2026-11-08T01:30", "America/Chicago"), "startOffset", "-06:00"));
        Instant start = stored(id, "starts_at"), end = stored(id, "ends_at");
        String read = mockMvc.perform(get("/api/admin/ads").header("Authorization", bearer)).andReturn().getResponse().getContentAsString();
        String startLocal = JsonPath.read(read, "$[0].startLocal"), startOffset = JsonPath.read(read, "$[0].startOffset");
        String endLocal = JsonPath.read(read, "$[0].endLocal"), endOffset = JsonPath.read(read, "$[0].endOffset");
        assertThat(startLocal).isEqualTo("2026-11-01T01:30");
        assertThat(startOffset).isEqualTo("-06:00");

        String unchanged = with(with(with(ad(startLocal, endLocal, "America/Chicago"), "startOffset", startOffset), "endOffset", endOffset), "rowVersion", 0);
        update(mockMvc, bearer, id, unchanged).andExpect(status().isOk());
        assertThat(stored(id, "starts_at")).isEqualTo(start);
        assertThat(stored(id, "ends_at")).isEqualTo(end);

        // the same instants expressed in another zone
        update(mockMvc, bearer, id, with(ad("2026-11-01T10:30", "2026-11-08T10:30", "Africa/Addis_Ababa"), "rowVersion", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleTimeZone").value("Africa/Addis_Ababa"));
        assertThat(stored(id, "starts_at")).isEqualTo(start);
        assertThat(stored(id, "ends_at")).isEqualTo(end);
    }
}
