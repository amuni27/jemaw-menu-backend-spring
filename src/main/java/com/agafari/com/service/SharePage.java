package com.agafari.com.service;

import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * AGF-S2-21 (approach A): a tiny HTML page with the restaurant's Open Graph tags for chat-app link previews, which
 * sends people on to the guest menu. Built only from public data (name, city, logo); every value is HTML-escaped.
 * The redirect always goes to {configured base}/{encoded business id}/menu, never to anything taken from the request.
 */
public final class SharePage {

    public static final String SITE = "Agafari";
    public static final String GENERIC_DESCRIPTION = "Browse restaurant menus on Agafari.";

    /** Public restaurant data for the preview. {@code logoUrl} is the public image URL or null. */
    public record Data(String businessId, String name, String city, String logoUrl) {
    }

    public record Result(int status, String html) {
    }

    private SharePage() {
    }

    /** {@code data == null}: unknown restaurant (404, generic tags, no redirect). An invalid base fails closed (500). */
    public static Result render(String configuredBase, Data data) {
        if (data == null) {
            return new Result(404, page(SITE, GENERIC_DESCRIPTION, null, null,
                    "<p>We can't find this restaurant's menu.</p>"));
        }
        Optional<String> base = PublicMenuBaseUrl.normalize(configuredBase);
        if (base.isEmpty()) {
            return new Result(500, page(SITE, GENERIC_DESCRIPTION, null, null,
                    "<p>This menu link isn't available right now.</p>"));
        }

        String menuUrl = base.get() + "/" + URLEncoder.encode(data.businessId(), StandardCharsets.UTF_8).replace("+", "%20") + "/menu";
        String city = data.city() == null || data.city().isBlank() ? "" : ", " + data.city().trim();
        String description = "See " + data.name() + "'s menu" + city + " on " + SITE + ".";
        String logo = data.logoUrl() != null && data.logoUrl().matches("(?i)^https?://.*") ? data.logoUrl() : null;

        String body = "<p><a href=\"" + esc(menuUrl) + "\">Open the menu</a></p>";
        return new Result(200, page(data.name(), description, logo, menuUrl, body));
    }

    private static String page(String title, String description, String imageUrl, String menuUrl, String body) {
        StringBuilder head = new StringBuilder()
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(title)).append("</title>\n")
                .append("<meta name=\"description\" content=\"").append(esc(description)).append("\">\n")
                .append("<meta property=\"og:site_name\" content=\"").append(SITE).append("\">\n")
                .append("<meta property=\"og:type\" content=\"website\">\n")
                .append("<meta property=\"og:title\" content=\"").append(esc(title)).append("\">\n")
                .append("<meta property=\"og:description\" content=\"").append(esc(description)).append("\">\n")
                .append("<meta name=\"twitter:card\" content=\"summary\">\n");
        if (imageUrl != null) {
            head.append("<meta property=\"og:image\" content=\"").append(esc(imageUrl)).append("\">\n");
        }
        if (menuUrl != null) {
            head.append("<meta property=\"og:url\" content=\"").append(esc(menuUrl)).append("\">\n")
                    .append("<link rel=\"canonical\" href=\"").append(esc(menuUrl)).append("\">\n")
                    .append("<meta http-equiv=\"refresh\" content=\"0; url=").append(esc(menuUrl)).append("\">\n");
        }
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n" + head + "</head>\n<body>\n" + body + "\n</body>\n</html>\n";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name()).replace("'", "&#39;");
    }
}
