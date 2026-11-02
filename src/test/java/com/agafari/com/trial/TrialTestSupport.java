package com.agafari.com.trial;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** AGF E4-M5: registers a restaurant through the real API (where trials start) and reads it back. */
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

    static String businessOf(JdbcTemplate jdbc, String label) {
        return jdbc.queryForObject("select b.id from business b join users u on u.id = b.owner_user_id where u.email like ?",
                String.class, label + "-%@owner.test");
    }
}
