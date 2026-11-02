package com.agafari.com.ads;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** AGF E4-M7: admin ad API helpers. */
final class AdTestSupport {

    static final String FIELD = "$.fieldErrors[?(@.field == '%s')].message";

    private AdTestSupport() {
    }

    static String body(String title, String text, String link, String startLocal, String endLocal, String zone) {
        return "{\"title\":" + q(title) + ",\"body\":" + q(text) + ",\"linkUrl\":" + q(link) + ",\"startLocal\":" + q(startLocal)
                + (endLocal == null ? "" : ",\"endLocal\":" + q(endLocal)) + ",\"timeZone\":" + q(zone) + "}";
    }

    static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    static ResultActions create(MockMvc mockMvc, String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/admin/ads").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static String createId(MockMvc mockMvc, String bearer, String json) throws Exception {
        return JsonPath.read(create(mockMvc, bearer, json).andReturn().getResponse().getContentAsString(), "$.id");
    }

    static ResultActions update(MockMvc mockMvc, String bearer, String id, String json) throws Exception {
        return mockMvc.perform(put("/api/admin/ads/{id}", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static ResultActions action(MockMvc mockMvc, String bearer, String id, String action, int rowVersion) throws Exception {
        return mockMvc.perform(post("/api/admin/ads/{id}/" + action, id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":" + rowVersion + "}"));
    }

    /** The JSON object with one more field (rowVersion, offsets, Amharic text, …). */
    static String with(String json, String field, Object value) {
        return json.substring(0, json.length() - 1) + ",\"" + field + "\":" + (value instanceof String s ? q(s) : String.valueOf(value)) + "}";
    }
}
