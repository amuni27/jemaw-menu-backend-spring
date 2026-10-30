package com.agafari.com.location;

import com.agafari.com.service.GoogleMapsUrls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S2-12: Google Maps share links (D-S2-12-2/-3, D-REV-4, D-S2-12-P2). Exact https hosts, path rules, no user-info,
 * no non-default port, at most 2,048 characters; host lower-cased and an explicit :443 dropped; path, query and
 * fragment kept as submitted. Nothing is ever fetched.
 */
class GoogleMapsUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.google.com/maps/place/Abol+Cafe/@9.0,38.7,17z",
            "https://google.com/maps?q=9.0,38.7",
            "https://maps.google.com/?q=Bole+Road",
            "https://maps.google.com",
            "https://maps.app.goo.gl/AbC123xyz",
            "https://goo.gl/maps/AbC123xyz",
            "https://www.google.com/maps/@9.0,38.7,15z?entry=ttu#anchor"})
    void acceptsTheFiveFormats(String url) {
        assertThat(GoogleMapsUrls.normalize(url)).contains(url);
    }

    @Test
    void normalisesOnlyTheHostAndADefaultPort() {
        assertThat(GoogleMapsUrls.normalize("  https://WWW.Google.COM/maps/Place/X?Q=Y  ")).contains("https://www.google.com/maps/Place/X?Q=Y");
        assertThat(GoogleMapsUrls.normalize("https://maps.app.goo.gl:443/AbC")).contains("https://maps.app.goo.gl/AbC");
        assertThat(GoogleMapsUrls.normalize("HTTPS://maps.google.com/x")).contains("https://maps.google.com/x");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://www.google.com/maps/place/x", "javascript:alert(1)", "data:text/html,hi", "ftp://maps.google.com/x",
            "www.google.com/maps/place/x", "//maps.google.com/x",
            "https://google.com.evil.example/maps/x", "https://evil.example/maps.google.com", "https://google.com@evil.example/maps",
            "https://user:pass@maps.google.com/x", "https://mapsgoogle.com/x", "https://www.google.com.et/maps/x",
            "https://google.com.et/maps/x", "https://www.google.co.uk/maps/x", "https://evil.maps.google.com/x",
            "https://www.google.com/search?q=maps", "https://google.com/", "https://maps.app.goo.gl/", "https://maps.app.goo.gl",
            "https://goo.gl/xyz", "https://goo.gl/maps/", "https://maps.google.com:8443/x", "https://maps.google.com/a b",
            "https://maps.google.com/x\ty", "https://", ""})
    void rejectsUnsafeUnrelatedLookAlikeCountryAndMalformedLinks(String url) {
        assertThat(GoogleMapsUrls.normalize(url)).isEmpty();
    }

    @Test
    void enforcesTheMaximumLength() {
        String prefix = "https://www.google.com/maps/place/";
        String ok = prefix + "a".repeat(2048 - prefix.length());
        assertThat(ok).hasSize(2048);
        assertThat(GoogleMapsUrls.normalize(ok)).contains(ok);
        assertThat(GoogleMapsUrls.normalize(ok + "a")).isEmpty();
    }

    @Test
    void blankMeansNoLink() {
        assertThat(GoogleMapsUrls.isBlank(null)).isTrue();
        assertThat(GoogleMapsUrls.isBlank("   ")).isTrue();
        assertThat(GoogleMapsUrls.isBlank("https://maps.google.com")).isFalse();
    }

    @Test
    void neverFetchesTheLink() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/agafari/com/service/GoogleMapsUrls.java"))
                + Files.readString(Path.of("src/main/java/com/agafari/com/validation/GoogleMapsUrlValidator.java"));
        assertThat(source).doesNotContain("HttpClient").doesNotContain("openConnection").doesNotContain("RestTemplate")
                .doesNotContain("RestClient").doesNotContain("WebClient").doesNotContain("URL(").doesNotContain("toURL");
    }
}
