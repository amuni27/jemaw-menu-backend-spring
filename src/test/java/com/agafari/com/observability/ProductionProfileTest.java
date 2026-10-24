package com.agafari.com.observability;

import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-16 / PO-3: the prod profile logs no SQL and exposes no API documentation; actuator shows only
 * health and info. "test" is layered last only to supply the test database and dummy secrets.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
@Import({TestcontainersConfiguration.class, TestDataFactory.class})
@ExtendWith(OutputCaptureExtension.class)
class ProductionProfileTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private Environment env;
    @Autowired private TestDataFactory data;

    @MockitoBean private S3Client s3Client;
    @MockitoBean private S3Presigner s3Presigner;

    @AfterEach
    void clean() {
        data.deleteAll();
    }

    @Test
    void sqlIsNotLoggedInProduction(CapturedOutput output) throws Exception {
        assertThat(env.getProperty("spring.jpa.show-sql", Boolean.class, false)).isFalse();

        var owner = data.tenant("alpha");
        data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat");
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain("Hibernate:", "select ");
    }

    @Test
    void apiDocumentationIsNotExposedInProduction() throws Exception {
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/swagger-config"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void actuatorExposesOnlyHealthAndInfoWithoutSecrets() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("password"))));
        for (String path : new String[]{"/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/loggers", "/actuator/heapdump"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
