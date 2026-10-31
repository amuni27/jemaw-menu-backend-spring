package com.agafari.com.observability;

import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S1-16: server logs stay diagnostic without full emails, passwords, tokens or request payloads. */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends AbstractIntegrationTest {

    private static final String EMAIL = "privacy.owner@example-cafe.test";
    private static final String PASSWORD = "Sup3r-Secret-Pass!";

    private static final String REGISTER = """
            {"fullName":"Privacy Owner","email":"%s","password":"%s","businessName":"Privacy Cafe",
             "businessPhone":"+251911000111","streetAddress":"1 Test St","city":"Addis Ababa","state":"Addis Ababa",
             "zipcode":"","customSubdomain":"privacy-cafe","open24_7":true,
                 "locationUrl":"https://maps.app.goo.gl/TestCafe1"}""".formatted(EMAIL, PASSWORD);

    @Test
    void authFlowsNeverLogFullEmailsPasswordsOrTokens(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isBadRequest()); // duplicate email

        String token = JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody.here@example-cafe.test\",\"password\":\"whatever\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Pass-123\"}"))
                .andExpect(status().isOk());

        String logs = output.getAll();
        assertThat(logs).contains("event=auth.register.success", "event=auth.login.success", "event=auth.login.invalid_password");
        assertThat(logs).doesNotContain(EMAIL, "nobody.here@example-cafe.test", PASSWORD, "Another-Pass-123", "wrong-password", token);
        assertThat(logs).contains("p***@example-cafe.test"); // masked form keeps the domain for troubleshooting
    }

    @Test
    void menuCreationDoesNotLogTheRequestPayload(CapturedOutput output) throws Exception {
        var owner = data.tenant("alpha");
        String typeId = data.menuType("Lunch").getId();

        mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Payload Marker Menu\",\"menuTypeId\":\"" + typeId
                                + "\",\"description\":\"private-note-xyz\"}"))
                .andExpect(status().isCreated());

        assertThat(output.getAll()).doesNotContain("private-note-xyz", "MenuCreateRequest(");
    }

    @Test
    void currentUserEndpointDoesNotLogTheEmail(CapturedOutput output) throws Exception {
        var owner = data.tenant("alpha");
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(owner))).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(owner.owner().getEmail());
        assertThat(TestDataFactory.PASSWORD).isNotBlank();
    }
}
