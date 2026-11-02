package com.agafari.com.ads;

import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.agafari.com.ads.AdTestSupport.FIELD;
import static com.agafari.com.ads.AdTestSupport.action;
import static com.agafari.com.ads.AdTestSupport.body;
import static com.agafari.com.ads.AdTestSupport.create;
import static com.agafari.com.ads.AdTestSupport.createId;
import static com.agafari.com.ads.AdTestSupport.update;
import static com.agafari.com.ads.AdTestSupport.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M7 (AC1, AC3 lifecycle, AC14): the Super Admin manages the Agafari ad inventory within the E4-Q5 minimum policy.
 * Title ≤ 60, text ≤ 200 (optional Amharic, same limits), https links only, start required and end after start. Edits are
 * optimistic (409 on stale); pause, resume and archive; never deleted; archived ads are frozen. Every change is audited and
 * rejections too. Owners can't use the admin API.
 */
class AgafariAdAdminTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = data.adminBearer(data.admin("ads-admin"));
    }

    private String valid() {
        return body("Coffee week", "Try the new roast", "https://example.test/coffee", "2026-12-01T09:00", null, "UTC");
    }

    private int audits(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ?", Integer.class, action);
    }

    @Test
    void ac1TheSuperAdminCreatesAnAdWithOptionalAmharicTextAndItIsAudited() throws Exception {
        String json = with(with(valid(), "titleAm", "የቡና ሳምንት"), "bodyAm", "አዲሱን ይሞክሩ");
        create(mockMvc, bearer, json).andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Coffee week"))
                .andExpect(jsonPath("$.titleAm").value("የቡና ሳምንት"))
                .andExpect(jsonPath("$.linkUrl").value("https://example.test/coffee"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-01T09:00:00Z"))
                .andExpect(jsonPath("$.endsAt").doesNotExist())
                .andExpect(jsonPath("$.scheduleTimeZone").value("UTC"))
                .andExpect(jsonPath("$.servedImpressions").value(0))
                .andExpect(jsonPath("$.rowVersion").value(0));
        assertThat(audits("AD_CREATED")).isEqualTo(1);
        mockMvc.perform(get("/api/admin/ads").header("Authorization", bearer)).andExpect(jsonPath("$[0].title").value("Coffee week"));
    }

    @Test
    void ac1ValidationFollowsTheMinimumPolicyAndRejectionsAreAudited() throws Exception {
        String sixtyOne = "x".repeat(61), twoHundredOne = "y".repeat(201);
        create(mockMvc, bearer, body(sixtyOne, "ok", "https://example.test", "2026-12-01T09:00", null, "UTC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("title")).value(hasItem("Enter a title (at most 60 characters)")));
        create(mockMvc, bearer, body("ok", twoHundredOne, "https://example.test", "2026-12-01T09:00", null, "UTC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("body")).value(hasItem("Enter the text (at most 200 characters)")));
        create(mockMvc, bearer, with(valid(), "titleAm", sixtyOne)).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("titleAm")).value(hasItem("At most 60 characters")));
        for (String link : new String[] {"http://example.test", "javascript:alert(1)", "data:text/html,hi", "ftp://example.test", "https://", "example.test",
                "https://" + "a".repeat(2050) + ".test"}) {
            create(mockMvc, bearer, body("ok", "ok", link, "2026-12-01T09:00", null, "UTC")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath(FIELD.formatted("linkUrl")).exists());
        }
        create(mockMvc, bearer, body("ok", "ok", "https://example.test", null, null, "UTC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("startLocal")).value(hasItem("Enter a start date and time")));
        create(mockMvc, bearer, body("ok", "ok", "https://example.test", "2026-12-01T09:00", "2026-12-01T09:00", "UTC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("endLocal")).value(hasItem("The end must be after the start")));
        assertThat(jdbc.queryForObject("select count(*) from agafari_ad", Integer.class)).isZero();
        assertThat(audits("AD_CHANGE_REJECTED")).isPositive();
    }

    @Test
    void ac1EditsAreOptimisticAndPauseResumeArchiveFollowTheLifecycle() throws Exception {
        String id = createId(mockMvc, bearer, valid());
        String edited = with(body("Coffee month", "Longer offer", "https://example.test/coffee", "2026-12-01T09:00", "2027-01-01T00:00", "UTC"), "rowVersion", 0);
        update(mockMvc, bearer, id, edited).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Coffee month"))
                .andExpect(jsonPath("$.endsAt").value("2027-01-01T00:00:00Z")).andExpect(jsonPath("$.rowVersion").value(1));
        update(mockMvc, bearer, id, edited).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_VERSION_CONFLICT"));

        action(mockMvc, bearer, id, "pause", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        action(mockMvc, bearer, id, "pause", 2).andExpect(status().isBadRequest());
        action(mockMvc, bearer, id, "resume", 2).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        action(mockMvc, bearer, id, "archive", 3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        action(mockMvc, bearer, id, "resume", 4).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_ARCHIVED"));
        update(mockMvc, bearer, id, with(valid(), "rowVersion", 4)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_ARCHIVED"));

        mockMvc.perform(delete("/api/admin/ads/{id}", id).header("Authorization", bearer)).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("select count(*) from agafari_ad", Integer.class)).isEqualTo(1);
        assertThat(audits("AD_UPDATED")).isEqualTo(1);
        assertThat(audits("AD_PAUSED")).isEqualTo(1);
        assertThat(audits("AD_RESUMED")).isEqualTo(1);
        assertThat(audits("AD_ARCHIVED")).isEqualTo(1);
    }

    @Test
    void ac1OwnersCannotUseTheAdminAdApi() throws Exception {
        String owner = data.bearer(data.tenant("ads-owner"));
        mockMvc.perform(get("/api/admin/ads").header("Authorization", owner)).andExpect(status().isForbidden());
        create(mockMvc, owner, valid()).andExpect(status().isForbidden());
    }
}
