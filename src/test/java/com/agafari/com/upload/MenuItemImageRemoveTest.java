package com.agafari.com.upload;

import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import com.agafari.com.jpa.repository.MenuItemRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-16: DELETE /api/items/{itemId}/image clears the item's photo and deletes its storage object best-effort,
 * only under the item's own prefix (D-S2-16-2, -3). Confirming a photo of another type deletes the previous object
 * (D-S2-16-4). New log lines carry no storage keys (D-S2-16-7).
 */
@ExtendWith(OutputCaptureExtension.class)
class MenuItemImageRemoveTest extends AbstractIntegrationTest {

    @Autowired private MenuItemRepository items;

    private Tenant owner;
    private Menu lunch;
    private MenuItem doro;
    private String doroKey;

    @BeforeEach
    void itemWithAPhoto() {
        owner = data.tenant("alpha");
        lunch = data.menu(owner, "Lunch");
        doro = data.item(data.category(lunch, "Mains"), "Doro Wat");
        doro.setDescription("Chicken stew");
        doro.setPrice(new BigDecimal("12.50"));
        doro.setTags(List.of("spicy"));
        doroKey = "agafari/menu-items/" + doro.getId() + "/main.jpg";
        doro.setImageUrl(doroKey);
        doro = items.save(doro);
    }

    private ResultActions removeImage(String itemId, Tenant who) throws Exception {
        var req = delete("/api/items/{id}/image", itemId);
        return mockMvc.perform(who == null ? req : req.header("Authorization", data.bearer(who)));
    }

    private MenuItem reload(MenuItem item) {
        return items.findById(item.getId()).orElseThrow();
    }

    private List<String> deletedKeys(int expectedCalls) {
        var deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(expectedCalls)).deleteObject(deleted.capture());
        return deleted.getAllValues().stream().map(DeleteObjectRequest::key).toList();
    }

    // AC4, AC5
    @Test
    void removingClearsThePhotoKeepsTheOtherFieldsAndDeletesTheObject() throws Exception {
        removeImage(doro.getId(), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doro.getId()))
                .andExpect(jsonPath("$.imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.name").value("Doro Wat"));

        MenuItem after = reload(doro);
        assertThat(after.getImageUrl()).isNull();
        assertThat(after.getDescription()).isEqualTo("Chicken stew");
        assertThat(after.getPrice()).isEqualByComparingTo("12.50");
        assertThat(after.getTags()).containsExactly("spicy");
        assertThat(deletedKeys(1)).containsExactly(doroKey);
    }

    // AC4
    @Test
    void removingTwiceIsFineAndDeletesOnce() throws Exception {
        removeImage(doro.getId(), owner).andExpect(status().isOk());
        removeImage(doro.getId(), owner).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(nullValue()));
        assertThat(deletedKeys(1)).containsExactly(doroKey);
    }

    // AC5, AC14
    @Test
    void whenStorageRefusesThePhotoIsStillRemovedAndTheKeyIsNotLogged(CapturedOutput output) throws Exception {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("denied " + doroKey));

        removeImage(doro.getId(), owner).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(nullValue()));

        assertThat(reload(doro).getImageUrl()).isNull();
        assertThat(output.getAll()).contains("event=item.image.removed").contains("event=item.image.delete_failed");
        assertThat(output.getAll()).doesNotContain(doroKey);
    }

    // AC6
    @Test
    void anImageOutsideTheItemsOwnPrefixIsOnlyUnreferenced() throws Exception {
        MenuItem other = data.item(data.category(lunch, "Drinks"), "Tea");
        doro.setImageUrl("agafari/menu-items/" + other.getId() + "/main.jpg");
        items.save(doro);
        MenuItem external = data.item(data.category(lunch, "Sides"), "Salad");
        external.setImageUrl("https://elsewhere.example/salad.jpg");
        items.save(external);

        removeImage(doro.getId(), owner).andExpect(status().isOk());
        removeImage(external.getId(), owner).andExpect(status().isOk());

        assertThat(reload(doro).getImageUrl()).isNull();
        assertThat(reload(external).getImageUrl()).isNull();
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // AC6 with S2-18
    @Test
    void aDuplicatedMenuKeepsItsOwnPhotoWhenTheSourcePhotoIsRemoved() throws Exception {
        var copy = mockMvc.perform(post("/api/menus/{id}/duplicate", lunch.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String copyMenuId = JsonPath.read(copy, "$.menu.id");
        MenuItem copied = items.findAll().stream()
                .filter(i -> i.getMenu().getId().equals(copyMenuId)).findFirst().orElseThrow();
        String copiedKey = copied.getImageUrl();
        assertThat(copiedKey).startsWith("agafari/menu-items/" + copied.getId() + "/");

        removeImage(doro.getId(), owner).andExpect(status().isOk());
        assertThat(reload(copied).getImageUrl()).isEqualTo(copiedKey);
        assertThat(deletedKeys(1)).containsExactly(doroKey);

        removeImage(copied.getId(), owner).andExpect(status().isOk());
        assertThat(deletedKeys(2)).containsExactly(doroKey, copiedKey);
    }

    // AC7 / D-S2-16-4
    @Test
    void confirmingAPhotoOfAnotherTypeDeletesThePreviousObject() throws Exception {
        String pngKey = "agafari/menu-items/" + doro.getId() + "/main.png";
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1024L).contentType("image/png").build());

        confirm(pngKey).andExpect(status().isOk());

        assertThat(reload(doro).getImageUrl()).isEqualTo(pngKey);
        assertThat(deletedKeys(1)).containsExactly(doroKey);
    }

    // AC7
    @Test
    void confirmingAPhotoOfTheSameTypeDeletesNothing() throws Exception {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1024L).contentType("image/jpeg").build());

        confirm(doroKey).andExpect(status().isOk());

        assertThat(reload(doro).getImageUrl()).isEqualTo(doroKey);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // AC7: a failed cleanup never fails the new photo
    @Test
    void aFailedCleanupOfThePreviousObjectStillSavesTheNewPhoto() throws Exception {
        String webpKey = "agafari/menu-items/" + doro.getId() + "/main.webp";
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1024L).contentType("image/webp").build());
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("denied"));

        confirm(webpKey).andExpect(status().isOk());
        assertThat(reload(doro).getImageUrl()).isEqualTo(webpKey);
    }

    private ResultActions confirm(String key) throws Exception {
        return mockMvc.perform(post("/api/items/{id}/image/confirm", doro.getId()).header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"objectKey\":\"" + key + "\"}"));
    }

    // AC8
    @Test
    void anotherRestaurantGetsNotFoundAndNothingChanges() throws Exception {
        var other = data.tenant("beta");

        removeImage(doro.getId(), other).andExpect(status().isNotFound());
        removeImage(doro.getId(), null).andExpect(status().isUnauthorized());
        removeImage("no-such-item", owner).andExpect(status().isNotFound());

        assertThat(reload(doro).getImageUrl()).isEqualTo(doroKey);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // AC9
    @Test
    void theUpdateEndpointStillRejectsImageUrlAndKeepsThePhotoOtherwise() throws Exception {
        String base = "{\"name\":\"Doro Wat\",\"price\":12.50,\"categoryId\":\"" + doro.getCategory().getId() + "\",\"ingredients\":[\"chicken\"]";
        mockMvc.perform(patch("/api/items/{id}", doro.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(base + ",\"imageUrl\":\"x.jpg\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/items/{id}", doro.getId()).header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(base + ",\"description\":\"New\"}"))
                .andExpect(status().isOk());

        assertThat(reload(doro).getImageUrl()).isEqualTo(doroKey);
    }

    // AC1
    @Test
    void guestsAndThePreviewNoLongerGetThePhoto() throws Exception {
        removeImage(doro.getId(), owner).andExpect(status().isOk());

        mockMvc.perform(get("/venue/{businessId}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Doro Wat")))
                .andExpect(content().string(not(containsString("menu-items"))));
        mockMvc.perform(get("/api/menus/{id}/preview", lunch.getId()).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("menu-items"))));
    }
}
