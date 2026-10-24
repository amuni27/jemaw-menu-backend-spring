package com.agafari.com.upload;

import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S1-17 / PO-5: server-side image type, size and object-key rules for item images and logos. */
class ImageUploadValidationTest extends AbstractIntegrationTest {

    private static final long MB = 1024L * 1024L;

    @Autowired private MenuItemRepository items;
    @Autowired private BusinessRepository businesses;

    private Tenant owner;
    private String itemId;

    @BeforeEach
    void setUp() throws Exception {
        owner = data.tenant("alpha");
        itemId = data.item(data.category(data.menu(owner, "Lunch"), "Mains"), "Doro Wat").getId();
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://r2.test.invalid/upload").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    private void storedObject(long size, String contentType) {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(size).contentType(contentType).build());
    }

    private String imageUrl() {
        return items.findById(itemId).orElseThrow().getImageUrl();
    }

    // ------------------------------------------------------------------ presign

    @Test
    void presignRejectsNonImageTypesWithoutIssuingAUrl() throws Exception {
        for (String type : new String[]{"application/pdf", "image/gif", "image/svg+xml", "text/html"}) {
            mockMvc.perform(get("/api/items/{id}/image/presign", itemId).param("contentType", type)
                            .header("Authorization", data.bearer(owner)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Unsupported image type. Upload a JPEG, PNG or WebP image."))
                    .andExpect(jsonPath("$.upload").doesNotExist());
        }
        verify(s3Presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void heicIsRejectedWithAClearMessage() throws Exception {
        mockMvc.perform(get("/api/items/{id}/image/presign", itemId).param("contentType", "image/heic")
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("HEIC photos aren't supported yet. Please upload a JPEG, PNG or WebP image."));
    }

    @Test
    void presignRequiresAContentType() throws Exception {
        mockMvc.perform(get("/api/items/{id}/image/presign", itemId).header("Authorization", data.bearer(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter 'contentType'"));
    }

    @Test
    void presignBuildsTheKeyFromTheContentTypeAndSignsIt() throws Exception {
        mockMvc.perform(get("/api/items/{id}/image/presign", itemId).param("contentType", "image/png")
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.objectKey").value("agafari/menu-items/" + itemId + "/main.png"));

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/png");
        assertThat(captor.getValue().putObjectRequest().key()).isEqualTo("agafari/menu-items/" + itemId + "/main.png");
        assertThat(imageUrl()).as("the key is saved only after a successful confirm").isNull();
    }

    @Test
    void createWithImageIgnoresTheClientFileNameForTheKey() throws Exception {
        String menuId = data.menu(owner, "Dinner").getId();
        String categoryId = data.category(menus(menuId), "Grill").getId();

        String body = mockMvc.perform(post("/api/menus/{id}/items", menuId).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"name\":\"Tibs\",\"price\":10,\"ingredients\":[\"beef\"],"
                                + "\"image\":{\"fileName\":\"x.jpg/../../other\",\"contentType\":\"image/jpeg\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String createdId = JsonPath.read(body, "$.item.id");
        assertThat((String) JsonPath.read(body, "$.upload.objectKey")).isEqualTo("agafari/menu-items/" + createdId + "/main.jpg");
    }

    @Test
    void createWithANonImageTypeIsRejectedAndCreatesNothing() throws Exception {
        String menuId = data.menu(owner, "Dinner").getId();
        String categoryId = data.category(menus(menuId), "Grill").getId();
        long before = items.count();

        mockMvc.perform(post("/api/menus/{id}/items", menuId).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"name\":\"Tibs\",\"price\":10,\"ingredients\":[\"beef\"],"
                                + "\"image\":{\"fileName\":\"menu.pdf\",\"contentType\":\"application/pdf\"}}"))
                .andExpect(status().isBadRequest());

        assertThat(items.count()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ confirm

    private void confirm(String key, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/items/{id}/image/confirm", itemId).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void validUploadIsSavedOnConfirmAndDisplayed() throws Exception {
        String key = "agafari/menu-items/" + itemId + "/main.webp";
        storedObject(2 * MB, "image/webp");

        confirm(key, 200);

        assertThat(imageUrl()).isEqualTo(key);
        mockMvc.perform(get("/api/items/{id}", itemId).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.imageUrl").value("https://images.test.invalid/" + key));
    }

    @Test
    void oversizedUploadIsDeletedAndLeavesNoBrokenReference() throws Exception {
        String key = "agafari/menu-items/" + itemId + "/main.jpg";
        storedObject(9 * MB, "image/jpeg");

        mockMvc.perform(post("/api/items/{id}/image/confirm", itemId).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image is too large. The maximum size is 8 MB."));

        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(key);
        assertThat(imageUrl()).isNull();
    }

    @Test
    void storedTypeThatIsNotAllowedOrDoesNotMatchIsDeleted() throws Exception {
        storedObject(MB, "image/gif");
        confirm("agafari/menu-items/" + itemId + "/main.png", 400);

        storedObject(MB, "image/jpeg"); // signed for PNG, stored as JPEG
        confirm("agafari/menu-items/" + itemId + "/main.png", 400);

        verify(s3Client, org.mockito.Mockito.times(2)).deleteObject(any(DeleteObjectRequest.class));
        assertThat(imageUrl()).isNull();
    }

    @Test
    void failedReuploadKeepsTheExistingDifferentImage() throws Exception {
        setImage("agafari/menu-items/" + itemId + "/main.jpg");
        storedObject(12 * MB, "image/png");

        confirm("agafari/menu-items/" + itemId + "/main.png", 400);

        assertThat(imageUrl()).isEqualTo("agafari/menu-items/" + itemId + "/main.jpg");
    }

    @Test
    void failedReuploadOverTheSameKeyClearsTheReference() throws Exception {
        String key = "agafari/menu-items/" + itemId + "/main.jpg";
        setImage(key); // the PUT overwrote this object, then the object was deleted
        storedObject(12 * MB, "image/jpeg");

        confirm(key, 400);

        assertThat(imageUrl()).isNull();
    }

    @Test
    void confirmRejectsKeysOutsideTheItemsOwnPrefix() throws Exception {
        String otherItem = data.item(data.category(data.menu(owner, "Other"), "Misc"), "Other").getId();
        for (String key : new String[]{
                "agafari/menu-items/" + otherItem + "/main.jpg",
                "agafari/menu-items/" + itemId + "/main.jpg/../../x",
                "agafari/menu-items/" + itemId + "/evil.jpg",
                "business-logo-asset/" + owner.businessId() + "/main.png"}) {
            confirm(key, 400);
        }
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void imagesUploadedBeforeThisChangeStillDisplay() throws Exception {
        setImage("agafari/menu-items/" + itemId + "/main.jpeg"); // legacy key built from a file name
        mockMvc.perform(get("/api/items/{id}", itemId).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.imageUrl").value("https://images.test.invalid/agafari/menu-items/" + itemId + "/main.jpeg"));
        mockMvc.perform(get("/venue/{b}/items/{i}", owner.businessId(), itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://images.test.invalid/agafari/menu-items/" + itemId + "/main.jpeg"));
    }

    // ------------------------------------------------------------------ logo

    @Test
    void logoPresignAllowsOnlyImagesAndBuildsTheKeyFromTheType() throws Exception {
        mockMvc.perform(post("/api/business/{id}/logo/presign", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"logo.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/business/{id}/logo/presign", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"a.png/../../x\",\"contentType\":\"image/png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("business-logo-asset/" + owner.businessId() + "/main.png"));

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/png");
        assertThat(businesses.findById(owner.businessId()).orElseThrow().getLogoKey()).isNull();
    }

    @Test
    void logoConfirmValidatesSizeAndTypeAndSavesOnlyValidUploads() throws Exception {
        String key = "business-logo-asset/" + owner.businessId() + "/main.png";
        storedObject(9 * MB, "image/png");
        mockMvc.perform(post("/api/business/{id}/logo/confirm", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        assertThat(businesses.findById(owner.businessId()).orElseThrow().getLogoKey()).isNull();

        storedObject(MB, "image/png");
        mockMvc.perform(post("/api/business/{id}/logo/confirm", owner.businessId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key + "\"}"))
                .andExpect(status().isOk());
        assertThat(businesses.findById(owner.businessId()).orElseThrow().getLogoKey()).isEqualTo(key);
    }

    // ------------------------------------------------------------------ helpers

    private com.agafari.com.jpa.entity.Menu menus(String menuId) {
        return menuRepository.findById(menuId).orElseThrow();
    }

    @Autowired private com.agafari.com.jpa.repository.MenuRepository menuRepository;

    private void setImage(String key) {
        var item = items.findById(itemId).orElseThrow();
        item.setImageUrl(key);
        items.save(item);
    }
}
