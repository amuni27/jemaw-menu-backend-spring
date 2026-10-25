package com.agafari.com.share;

import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-21: GET /venue/{businessId}/share is public and returns the restaurant's share page (Open Graph tags from the
 * existing public data: name, city, logo) that redirects people to the guest menu at the configured base URL.
 */
class PublicSharePageTest extends AbstractIntegrationTest {

    @Autowired private BusinessRepository businesses;

    private Tenant abol;

    @BeforeEach
    void restaurantWithALogo() {
        abol = data.tenant("abol");
        abol.business().setLogoKey("agafari/logos/" + abol.businessId() + "/logo.png");
        businesses.save(abol.business());
    }

    @Test
    void shareFetchedLikeACrawlerReturnsTheRestaurantsTagsWithoutAToken() throws Exception {
        String menu = "https://frontend.test.invalid/" + abol.businessId() + "/menu";
        mockMvc.perform(get("/venue/{id}/share", abol.businessId()).header("User-Agent", "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/html")))
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(content().string(containsString("<meta property=\"og:title\" content=\"abol Restaurant\">")))
                .andExpect(content().string(containsString("See abol Restaurant&#39;s menu, Addis Ababa on Agafari.")))
                .andExpect(content().string(containsString(
                        "<meta property=\"og:image\" content=\"https://images.test.invalid/agafari/logos/" + abol.businessId() + "/logo.png\">")))
                .andExpect(content().string(containsString("<meta property=\"og:url\" content=\"" + menu + "\">")))
                .andExpect(content().string(containsString("url=" + menu)));
    }

    @Test
    void anUnknownRestaurantIs404WithoutARedirectOrOtherData() throws Exception {
        mockMvc.perform(get("/venue/{id}/share", "no-such-business"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("text/html")))
                .andExpect(content().string(containsString("<meta property=\"og:title\" content=\"Agafari\">")))
                .andExpect(content().string(not(containsString("http-equiv=\"refresh\""))));
    }

    @Test
    void onlyPublicFieldsAppear() throws Exception {
        mockMvc.perform(get("/venue/{id}/share", abol.businessId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("abol@test.agafari.local"))))
                .andExpect(content().string(not(containsString("+251911000000"))))
                .andExpect(content().string(not(containsString("1 Test Street"))));
    }
}
