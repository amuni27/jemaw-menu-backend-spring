package com.agafari.com.controller;

import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S2-01: the restaurant logo is shown to owners and guests, and owners can remove it. */
class BusinessLogoTest extends AbstractIntegrationTest {

    private static final String PUBLIC_BASE = "https://images.test.invalid/";

    @Autowired private BusinessRepository businesses;

    private Tenant owner;

    @BeforeEach
    void setUp() {
        owner = data.tenant("alpha");
        data.menu(owner, "Lunch");
    }

    private String logoKey(Tenant tenant) {
        return businesses.findById(tenant.businessId()).orElseThrow().getLogoKey();
    }

    private void setLogoKey(Tenant tenant, String key) {
        var b = businesses.findById(tenant.businessId()).orElseThrow();
        b.setLogoKey(key);
        businesses.save(b);
    }

    private String key(Tenant tenant, String ext) {
        return "business-logo-asset/" + tenant.businessId() + "/main." + ext;
    }

    @Test
    void guestMenuAndOwnerSessionCarryTheLogoUrl() throws Exception {
        setLogoKey(owner, key(owner, "png"));
        String url = PUBLIC_BASE + key(owner, "png");

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.logoUrl").value(url));
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.business.logoUrl").value(url));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"" + TestDataFactory.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.logoUrl").value(url));
    }

    @Test
    void withoutALogoTheUrlIsNull() throws Exception {
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.logoUrl").doesNotExist());
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.business.logoUrl").doesNotExist());
    }

    @Test
    void ownerCanRemoveTheLogo() throws Exception {
        setLogoKey(owner, key(owner, "png"));

        mockMvc.perform(delete("/api/business/{id}/logo", owner.businessId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.businessId()))
                .andExpect(jsonPath("$.logoUrl").doesNotExist());

        assertThat(logoKey(owner)).isNull();
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo(key(owner, "png"));

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.logoUrl").doesNotExist());
    }

    @Test
    void removalSucceedsWhenStorageCleanupFails() throws Exception {
        setLogoKey(owner, key(owner, "png"));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("storage unavailable").build());

        mockMvc.perform(delete("/api/business/{id}/logo", owner.businessId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk());

        assertThat(logoKey(owner)).isNull();
    }

    @Test
    void removingWhenThereIsNoLogoIsHarmless() throws Exception {
        mockMvc.perform(delete("/api/business/{id}/logo", owner.businessId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").doesNotExist());

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void replacingTheLogoWithAnotherTypeRemovesTheOldObject() throws Exception {
        setLogoKey(owner, key(owner, "png"));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1024L).contentType("image/webp").build());

        mockMvc.perform(post("/api/business/{id}/logo/confirm", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key(owner, "webp") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(PUBLIC_BASE + key(owner, "webp")));

        assertThat(logoKey(owner)).isEqualTo(key(owner, "webp"));
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo(key(owner, "png"));
    }

    @Test
    void anotherOwnerCannotPresignConfirmOrRemoveTheLogo() throws Exception {
        Tenant other = data.tenant("bravo");
        setLogoKey(owner, key(owner, "png"));
        String otherBearer = data.bearer(other);

        mockMvc.perform(post("/api/business/{id}/logo/presign", owner.businessId()).header("Authorization", otherBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"logo.png\",\"contentType\":\"image/png\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/business/{id}/logo/confirm", owner.businessId()).header("Authorization", otherBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key(owner, "jpg") + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/business/{id}/logo", owner.businessId()).header("Authorization", otherBearer))
                .andExpect(status().isForbidden());

        assertThat(logoKey(owner)).isEqualTo(key(owner, "png"));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void removingRequiresAuthentication() throws Exception {
        setLogoKey(owner, key(owner, "png"));
        mockMvc.perform(delete("/api/business/{id}/logo", owner.businessId()))
                .andExpect(status().isUnauthorized());
        assertThat(logoKey(owner)).isEqualTo(key(owner, "png"));
    }
}
