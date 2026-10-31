package com.agafari.com.controller;

import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.service.LogoUrls;
import com.agafari.com.service.SharePage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * AGF-S2-21: public share page for chat-app link previews (under the already public /venue/**).
 * GET /venue/{businessId}/share → HTML with the restaurant's Open Graph tags, redirecting people to the guest menu.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PublicSharePageController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final BusinessRepository businessRepo;

    @Value("${app.public-menu-base-url:}")
    private String publicMenuBaseUrl;

    @Value("${cloudflare.publicBaseUrl}")
    private String imagesBaseUrl;

    @GetMapping("/venue/{businessId}/share")
    public ResponseEntity<String> share(@PathVariable String businessId) {
        SharePage.Data data = businessRepo.findById(businessId)
                .map(b -> new SharePage.Data(b.getId(), b.getName(), b.getCity(), LogoUrls.publicUrl(imagesBaseUrl, b.getLogoKey())))
                .orElse(null);
        SharePage.Result page = SharePage.render(publicMenuBaseUrl, data);

        if (page.status() == 500) {
            // the configured value is never logged
            log.error("event=share_page.invalid_public_menu_base_url");
        }
        CacheControl cache = page.status() == 500 ? CacheControl.noStore() : CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic();
        return ResponseEntity.status(page.status()).contentType(HTML).cacheControl(cache).body(page.html());
    }
}
