package com.agafari.com.ads;

import com.agafari.com.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;

import static com.agafari.com.ads.AdTestSupport.FIELD;
import static com.agafari.com.ads.AdTestSupport.body;
import static com.agafari.com.ads.AdTestSupport.createId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M7 (AC2, C4): an ad image goes through the existing presign → upload → confirm flow and image policy, under the
 * ad's own prefix, with required alt text. Storage is the mocked S3 client of the test harness: no real or exposed R2
 * credentials are used.
 */
class AdImageTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String bearer;
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        bearer = data.adminBearer(data.admin("ads-image"));
        id = createId(mockMvc, bearer, body("With image", "Text", "https://example.test", "2026-12-01T09:00", null, "UTC"));
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://r2.test.invalid/upload").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    private void storedObject(long size, String contentType) {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(size).contentType(contentType).build());
    }

    private String presign(String contentType) throws Exception {
        String json = mockMvc.perform(post("/api/admin/ads/{id}/image/presign", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"" + contentType + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://r2.test.invalid/upload")).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.objectKey");
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String key, String alt, int rowVersion) throws Exception {
        return mockMvc.perform(post("/api/admin/ads/{id}/image/confirm", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"" + key + "\",\"imageAlt\":" + (alt == null ? "null" : "\"" + alt + "\"") + ",\"rowVersion\":" + rowVersion + "}"));
    }

    @Test
    void ac2AnImageIsPresignedUnderTheAdPrefixAndConfirmedWithAltText() throws Exception {
        String key = presign("image/png");
        assertThat(key).startsWith("agafari-ad-asset/" + id + "/").endsWith(".png");
        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/png");

        storedObject(1024, "image/png");
        confirm(key, "A cup of coffee", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.endsWith("/" + key)))
                .andExpect(jsonPath("$.imageAlt").value("A cup of coffee"));
        assertThat(jdbc.queryForObject("select image_key from agafari_ad where id = ?", String.class, id)).isEqualTo(key);

        mockMvc.perform(post("/api/admin/ads/{id}/image/remove", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist());
        assertThat(jdbc.queryForObject("select image_key is null and image_alt is null from agafari_ad where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void ac2UnsupportedTypesMissingAltTextAndForeignKeysAreRefused() throws Exception {
        mockMvc.perform(post("/api/admin/ads/{id}/image/presign", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/gif\"}")).andExpect(status().isBadRequest());
        verify(s3Presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));

        String key = presign("image/jpeg");
        storedObject(1024, "image/jpeg");
        confirm(key, null, 0).andExpect(status().isBadRequest()).andExpect(jsonPath(FIELD.formatted("imageAlt")).value(hasItem("Describe the image (at most 200 characters)")));
        confirm(key, "x".repeat(201), 0).andExpect(status().isBadRequest());
        confirm("business-logo-asset/other/x.jpg", "Alt", 0).andExpect(status().isBadRequest());
        storedObject(9L * 1024 * 1024, "image/jpeg");
        confirm(key, "Alt", 0).andExpect(status().isBadRequest()); // over the 8 MB policy
        assertThat(jdbc.queryForObject("select image_key is null from agafari_ad where id = ?", Boolean.class, id)).isTrue();
    }
}
