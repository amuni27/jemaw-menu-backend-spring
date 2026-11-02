package com.agafari.com.restaurantads;

import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.RestaurantAdService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;

import static com.agafari.com.restaurantads.RestaurantAdTestSupport.FIELD;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.OWNER;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.action;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.createId;
import static com.agafari.com.restaurantads.RestaurantAdTestSupport.valid;
import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M8 (AC12, Q-M8-13): a restaurant ad image goes through the existing presign → upload → confirm flow and image
 * policy under a restaurant-scoped key ({@code restaurant-ad-asset/{businessId}/{adId}}), with required alt text. Another
 * restaurant's key or an Agafari key is refused. Storage is the mocked S3 client of the test harness: no real or exposed R2
 * credentials are used.
 */
class RestaurantAdImageTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementService entitlements;
    @Autowired private RestaurantAdService restaurantAds;

    private Tenant t;
    private Tenant other;
    private String bearer;
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        var admin = data.admin("ra-image-admin");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        entitlements.setEnabled(true);
        restaurantAds.setEnabled(true);
        t = data.tenant("ra-image");
        other = data.tenant("ra-image-other");
        subscription(jdbc, t.businessId(), "MODEL_3", 1, admin.getId());
        bearer = data.bearer(t);
        id = createId(mockMvc, bearer, valid("With image"));
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://r2.test.invalid/upload").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        restaurantAds.setEnabled(false);
    }

    private void storedObject(long size, String contentType) {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(size).contentType(contentType).build());
    }

    private String presign(String contentType) throws Exception {
        String json = mockMvc.perform(post(OWNER + "/{id}/image/presign", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"" + contentType + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://r2.test.invalid/upload")).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.objectKey");
    }

    private ResultActions confirm(String key, String alt, int rowVersion) throws Exception {
        return mockMvc.perform(post(OWNER + "/{id}/image/confirm", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"" + key + "\",\"imageAlt\":" + (alt == null ? "null" : "\"" + alt + "\"") + ",\"rowVersion\":" + rowVersion + "}"));
    }

    @Test
    void ac12AnImageIsPresignedUnderTheRestaurantScopedKeyConfirmedWithAltTextAndServed() throws Exception {
        String key = presign("image/webp");
        assertThat(key).startsWith("restaurant-ad-asset/" + t.businessId() + "/" + id + "/").endsWith(".webp");
        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/webp");

        storedObject(2048, "image/webp");
        confirm(key, "A plate of tibs", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(endsWith("/" + key)))
                .andExpect(jsonPath("$.imageAlt").value("A plate of tibs"));
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'RESTAURANT_AD_IMAGE_CHANGED'", Integer.class)).isEqualTo(1);
        mockMvc.perform(get("/venue/{id}/menu", t.businessId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.sponsored.imageUrl").value(endsWith("/" + key)))
                .andExpect(jsonPath("$.sponsored.imageAlt").value("A plate of tibs"));

        action(mockMvc, bearer, id, "image/remove", 1).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist());
        assertThat(jdbc.queryForObject("select image_key is null and image_alt is null from restaurant_ad where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void ac12UnsupportedTypesMissingAltTextForeignKeysAndOversizedImagesAreRefused() throws Exception {
        mockMvc.perform(post(OWNER + "/{id}/image/presign", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/gif\"}")).andExpect(status().isBadRequest());
        verify(s3Presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));

        String key = presign("image/jpeg");
        storedObject(1024, "image/jpeg");
        confirm(key, null, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD.formatted("imageAlt")).value(hasItem("Describe the image (at most 200 characters)")));
        confirm(key, "x".repeat(201), 0).andExpect(status().isBadRequest());
        confirm("restaurant-ad-asset/" + other.businessId() + "/" + id + "/main.jpg", "Alt", 0).andExpect(status().isBadRequest()); // another restaurant
        confirm("agafari-ad-asset/" + id + "/main.jpg", "Alt", 0).andExpect(status().isBadRequest()); // an Agafari key
        confirm("business-logo-asset/" + t.businessId() + "/main.jpg", "Alt", 0).andExpect(status().isBadRequest());
        storedObject(9L * 1024 * 1024, "image/jpeg");
        confirm(key, "Alt", 0).andExpect(status().isBadRequest()); // over the 8 MB policy
        assertThat(jdbc.queryForObject("select image_key is null from restaurant_ad where id = ?", Boolean.class, id)).isTrue();
    }
}
