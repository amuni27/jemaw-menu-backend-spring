package com.agafari.com.restaurantads;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** AGF E4-M8: owner and Super Admin restaurant-ad API helpers. */
final class RestaurantAdTestSupport {

    static final String OWNER = "/api/restaurant-ads";
    static final String ADMIN = "/api/admin/restaurant-ads";
    static final String FIELD = "$.fieldErrors[?(@.field == '%s')].message";

    private RestaurantAdTestSupport() {
    }

    /** Owner request body: no time zone (the restaurant's own zone applies, Q-M8-7). */
    static String body(String title, String text, String link, String startLocal, String endLocal) {
        return "{\"title\":" + q(title) + ",\"body\":" + q(text) + ",\"linkUrl\":" + q(link) + ",\"startLocal\":" + q(startLocal)
                + (endLocal == null ? "" : ",\"endLocal\":" + q(endLocal)) + "}";
    }

    static String valid(String title) {
        return body(title, "Text " + title, "https://example.test/" + title.toLowerCase().replace(' ', '-'), "2026-01-01T00:00", null);
    }

    static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    /** The JSON object with one more field. */
    static String with(String json, String field, Object value) {
        return json.substring(0, json.length() - 1) + ",\"" + field + "\":" + (value instanceof String s ? q(s) : String.valueOf(value)) + "}";
    }

    static ResultActions list(MockMvc mockMvc, String bearer) throws Exception {
        return mockMvc.perform(get(OWNER).header("Authorization", bearer));
    }

    static ResultActions create(MockMvc mockMvc, String bearer, String json) throws Exception {
        return mockMvc.perform(post(OWNER).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String createId(MockMvc mockMvc, String bearer, String json) throws Exception {
        return JsonPath.read(create(mockMvc, bearer, json).andReturn().getResponse().getContentAsString(), "$.id");
    }

    static ResultActions update(MockMvc mockMvc, String bearer, String id, String json) throws Exception {
        return mockMvc.perform(put(OWNER + "/{id}", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** pause, resume, archive, image/remove (owner) */
    static ResultActions action(MockMvc mockMvc, String bearer, String id, String action, int rowVersion) throws Exception {
        return mockMvc.perform(post(OWNER + "/{id}/" + action, id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":" + rowVersion + "}"));
    }

    static ResultActions suspend(MockMvc mockMvc, String adminBearer, String id, String reason, int rowVersion) throws Exception {
        return mockMvc.perform(post(ADMIN + "/{id}/suspend", id).header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":" + q(reason) + ",\"rowVersion\":" + rowVersion + "}"));
    }

    static ResultActions reinstate(MockMvc mockMvc, String adminBearer, String id, int rowVersion) throws Exception {
        return mockMvc.perform(post(ADMIN + "/{id}/reinstate", id).header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":" + rowVersion + "}"));
    }
}
