package com.agafari.com.share;

import com.agafari.com.service.PublicMenuBaseUrl;
import com.agafari.com.service.SharePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S2-21: the share page HTML (approach A) and the public menu base URL rules (D-S2-21-5). Same rules and fallback
 * as the frontend (AGF-S2-20): https only (http just for local hosts), no credentials, query or fragment, and the
 * existing production frontend when unset. Never a hard-coded brand domain.
 */
class SharePageRendererTest {

    private static final String BASE = "https://menu.example.test";
    private static final String ID = "00000000-0000-4000-8000-0000000000b1";

    // ---------------------------------------------------------------- base URL

    @Test
    void unsetBaseFallsBackToTheSameAddressAsTheFrontend() {
        assertThat(PublicMenuBaseUrl.FALLBACK).isEqualTo("https://agafari-menu.onrender.com");
        assertThat(PublicMenuBaseUrl.normalize(null)).contains("https://agafari-menu.onrender.com");
        assertThat(PublicMenuBaseUrl.normalize("  ")).contains("https://agafari-menu.onrender.com");
    }

    @Test
    void validBasesAreNormalised() {
        assertThat(PublicMenuBaseUrl.normalize("https://Menu.Example.TEST:443//")).contains("https://menu.example.test");
        assertThat(PublicMenuBaseUrl.normalize("https://example.test/menus/")).contains("https://example.test/menus");
        assertThat(PublicMenuBaseUrl.normalize("http://localhost:5175")).contains("http://localhost:5175");
        assertThat(PublicMenuBaseUrl.normalize("http://127.0.0.1:5176/")).contains("http://127.0.0.1:5176");
        assertThat(PublicMenuBaseUrl.normalize("http://demo.localhost:5175")).contains("http://demo.localhost:5175");
    }

    @ParameterizedTest
    @ValueSource(strings = {"agafari-menu.onrender.com", "javascript:alert(1)", "ftp://x.test", "http://menu.example.test",
            "https://u:p@x.test", "https://x.test/?a=1", "https://x.test/#top", "https://x .test", "https://", "//x.test"})
    void unsafeOrInvalidBasesAreRejected(String raw) {
        assertThat(PublicMenuBaseUrl.normalize(raw)).isEmpty();
    }

    // ---------------------------------------------------------------- page

    private static SharePage.Data abol(String logo) {
        return new SharePage.Data(ID, "Abol Café", "Addis Ababa", logo);
    }

    @Test
    void restaurantPageHasItsOwnTagsAndRedirectsToTheGuestMenu() {
        SharePage.Result page = SharePage.render(BASE, abol("https://images.test/logo.png"));

        assertThat(page.status()).isEqualTo(200);
        String html = page.html();
        String menu = BASE + "/" + ID + "/menu";
        assertThat(html).contains("<title>Abol Café</title>")
                .contains("<meta property=\"og:title\" content=\"Abol Café\">")
                .contains("<meta property=\"og:description\" content=\"See Abol Café&#39;s menu, Addis Ababa on Agafari.\">")
                .contains("<meta name=\"description\" content=\"See Abol Café&#39;s menu, Addis Ababa on Agafari.\">")
                .contains("<meta property=\"og:image\" content=\"https://images.test/logo.png\">")
                .contains("<meta property=\"og:url\" content=\"" + menu + "\">")
                .contains("<meta property=\"og:site_name\" content=\"Agafari\">")
                .contains("<meta property=\"og:type\" content=\"website\">")
                .contains("<meta name=\"twitter:card\" content=\"summary\">")
                .contains("<meta http-equiv=\"refresh\" content=\"0; url=" + menu + "\">")
                .contains("<a href=\"" + menu + "\">Open the menu</a>")
                .doesNotContain("<script");
    }

    @Test
    void withoutALogoThereIsNoImageTagAndWithoutACityTheDescriptionIsShorter() {
        String html = SharePage.render(BASE, new SharePage.Data(ID, "Abol", null, null)).html();
        assertThat(html).doesNotContain("og:image").contains("content=\"See Abol&#39;s menu on Agafari.\"");
    }

    @Test
    void onlyHttpLogosAreUsed() {
        assertThat(SharePage.render(BASE, abol("javascript:alert(1)")).html()).doesNotContain("og:image");
        assertThat(SharePage.render(BASE, abol("data:image/png;base64,AAA")).html()).doesNotContain("og:image");
    }

    @Test
    void hostileValuesAreEscaped() {
        String html = SharePage.render(BASE, new SharePage.Data(ID, "<script>alert(\"x\")</script>", "\"><b>City",
                "https://images.test/a.png\" onerror=\"alert(1)")).html();
        assertThat(html).doesNotContain("<script>alert").doesNotContain("\"><b>").doesNotContain("\" onerror=\"")
                .contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;");
    }

    @Test
    void theRedirectIsBuiltFromTheConfiguredBaseAndTheEncodedIdOnly() {
        String html = SharePage.render(BASE, new SharePage.Data("a b/../evil", "X", null, null)).html();
        assertThat(html).contains("url=" + BASE + "/a%20b%2F..%2Fevil/menu\"");
    }

    @Test
    void unknownRestaurantGets404WithGenericTagsAndNoRedirect() {
        SharePage.Result page = SharePage.render(BASE, null);
        assertThat(page.status()).isEqualTo(404);
        assertThat(page.html()).contains("<meta property=\"og:title\" content=\"Agafari\">")
                .contains("<meta property=\"og:site_name\" content=\"Agafari\">")
                .doesNotContain("http-equiv=\"refresh\"");
    }

    @Test
    void anInvalidConfiguredBaseFailsClosedWithoutARedirect() {
        SharePage.Result page = SharePage.render("ftp://x.test", abol(null));
        assertThat(page.status()).isEqualTo(500);
        assertThat(page.html()).doesNotContain("http-equiv=\"refresh\"").doesNotContain("ftp://");
    }
}
