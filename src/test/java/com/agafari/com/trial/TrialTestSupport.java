package com.agafari.com.trial;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF E4-M5: registers a restaurant through the real API and reads it back. AGF BE-2 (register §45 D-3, §48): registration
 * no longer starts trials or records intents, so recovery tests seed the PENDING intents that registrations recorded before
 * BE-2 (Q1: those are still recovered).
 */
final class TrialTestSupport {

    private TrialTestSupport() {
    }

    static ResultActions register(MockMvc mockMvc, String label) throws Exception {
        String suffix = label + "-" + UUID.randomUUID().toString().substring(0, 6);
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Trial Owner","email":"%s@owner.test","password":"Str0ng!Pass1","businessName":"Trial %s",
                 "businessPhone":"+2519%s","customSubdomain":"%s","open24_7":true,"country":"ET",
                 "locationUrl":"https://maps.app.goo.gl/AbC123xyz"}""".formatted(suffix, suffix,
                String.valueOf(System.nanoTime()).substring(0, 8), suffix)));
    }

    /** AGF BE-2: a PENDING intent as a registration recorded it before BE-2 (its after-commit step never ran). */
    static void legacyPendingIntent(JdbcTemplate jdbc, String businessId, int configVersion) {
        jdbc.update("insert into trial_intent (business_id, config_version, status, failed_attempts, created_at, row_version) "
                + "values (?, ?, 'PENDING', 0, clock_timestamp(), 0)", businessId, configVersion);
    }

    static String businessOf(JdbcTemplate jdbc, String label) {
        return jdbc.queryForObject("select b.id from business b join users u on u.id = b.owner_user_id where u.email like ?",
                String.class, label + "-%@owner.test");
    }
}
