package com.agafari.com.exception;

import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-10: consistent, sanitized error responses { status, error, message, fieldErrors?, traceId? }.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorHandlingTest extends AbstractIntegrationTest {

    // Values from the test profile that must never appear in a response
    private static final List<String> SECRETS = List.of(
            "test-only-jwt-secret-not-used-anywhere-else-0123456789", "test-secret-key", "test-access-key");

    @Test
    void validationErrorReturns400WithFieldErrors() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");

        mockMvc.perform(post("/api/menus/{menuId}/items", menu.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":-1,\"categoryId\":\"c\",\"ingredients\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("price")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("ingredients")))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    @Test
    void updateRequestValidationIsEnforced() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(patch("/api/items/{id}", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calories\":-5,\"price\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("calories")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("price")));
    }

    @Test
    void emptyCategoryReorderIsRejected() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");

        mockMvc.perform(post("/api/menus/{menuId}/categories/reorder", menu.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("orderedIds")));
    }

    @Test
    void invalidEnumQueryParameterReturns400WithoutInternals() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");

        String body = mockMvc.perform(get("/api/menus/{menuId}/items", menu.getId())
                        .param("status", "FOO")
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'status'"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("MenuItemStatus", "java.", "Failed to convert", "FOO");
    }

    @Test
    void malformedJsonReturns400WithGenericMessage() throws Exception {
        var owner = data.tenant("alpha");
        var menu = data.menu(owner, "Lunch");

        String body = mockMvc.perform(post("/api/menus/{menuId}/categories", menu.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("JSON parse", "jackson", "Unexpected character", "line:");
    }

    @Test
    void invalidEnumInBodyReturns400() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(put("/api/items/{id}/status", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FOO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request"));
    }

    /** Completes AGF-S1-12 AC7. */
    @Test
    void unsupportedMethodReturns405WithAllowHeader() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");

        mockMvc.perform(patch("/api/items/{id}/status", item.getId())
                        .header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("PUT")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message").value("Request method 'PATCH' is not supported"));
    }

    @Test
    void unknownPathsReturn404() throws Exception {
        var owner = data.tenant("alpha");

        String body = mockMvc.perform(get("/api/no-such-endpoint").header("Authorization", data.bearer(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("static resource");

        mockMvc.perform(get("/venue/{businessId}/no-such-thing", owner.businessId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorReturnsSanitized500WithTraceIdThatIsLogged(CapturedOutput output) throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenThrow(new IllegalStateException("internal detail jdbc:postgresql://db.internal:5432/prod password=hunter2"));

        String body = mockMvc.perform(get("/api/items/{id}/image/presign", item.getId())
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("IllegalStateException", "jdbc", "hunter2", "internal detail", "at com.");

        Matcher m = Pattern.compile("\"traceId\":\"([0-9a-f-]{8,})\"").matcher(body);
        assertThat(m.find()).isTrue();
        String traceId = m.group(1);
        assertThat(output.getAll()).contains(traceId).contains("java.lang.IllegalStateException");
        // AGF-S1-16: the entry also identifies the endpoint and the caller
        assertThat(output.getAll()).contains("path=/api/items/" + item.getId() + "/image/presign")
                .contains("userId=" + owner.owner().getId())
                .contains("businessId=" + owner.businessId());
    }

    @Test
    void existingDomainErrorsKeepTheirStatusAndMessage() throws Exception {
        var a = data.tenant("alpha");
        var b = data.tenant("bravo");
        var category = data.category(data.menu(a, "Lunch"), "Mains");
        data.item(category, "Doro Wat");
        var bItem = data.item(data.category(data.menu(b, "Lunch"), "Mains"), "Pasta");

        mockMvc.perform(delete("/api/categories/{id}", category.getId()).header("Authorization", data.bearer(a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Category has items; delete items first"))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/items/{id}", bItem.getId()).header("Authorization", data.bearer(a)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Item not found"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + a.owner().getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void errorResponsesNeverContainConfiguredSecrets() throws Exception {
        var owner = data.tenant("alpha");
        var item = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(new RuntimeException("boom"));

        List<MvcResult> results = new ArrayList<>();
        results.add(mockMvc.perform(get("/api/items/{id}/image/presign", item.getId()).header("Authorization", data.bearer(owner))).andReturn());
        results.add(mockMvc.perform(patch("/api/items/{id}/status", item.getId()).header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn());
        results.add(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{bad")).andReturn());
        results.add(mockMvc.perform(get("/api/no-such-endpoint").header("Authorization", data.bearer(owner))).andReturn());

        for (MvcResult r : results) {
            String body = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            SECRETS.forEach(secret -> assertThat(body).doesNotContain(secret));
            assertThat(body).doesNotContain("Exception", "\tat ");
        }
    }
}
