package com.agafari.com.service;

import java.net.URI;
import java.util.Map;

/**
 * AGF E4-M7/E4-M8 (Q-M8-1, Q-M8-14): the shared ad content limits. Title ≤ 60, text ≤ 200 (optional Amharic, same limits),
 * https links only (≤ 2,048). Field errors use the approved wording.
 */
public final class AdContentRules {

    /** The trimmed values (blank optional text becomes null). */
    public record Text(String title, String body, String titleAm, String bodyAm, String linkUrl) {
    }

    private AdContentRules() {
    }

    public static Text check(String title, String body, String titleAm, String bodyAm, String linkUrl, Map<String, String> errors) {
        Text t = new Text(trim(title), trim(body), trim(titleAm), trim(bodyAm), trim(linkUrl));
        if (t.title() == null || t.title().length() > 60) errors.put("title", "Enter a title (at most 60 characters)");
        if (t.body() == null || t.body().length() > 200) errors.put("body", "Enter the text (at most 200 characters)");
        if (t.titleAm() != null && t.titleAm().length() > 60) errors.put("titleAm", "At most 60 characters");
        if (t.bodyAm() != null && t.bodyAm().length() > 200) errors.put("bodyAm", "At most 200 characters");
        if (!httpsLink(t.linkUrl())) errors.put("linkUrl", "Use an https:// link (at most 2,048 characters)");
        return t;
    }

    /** https only: refuses http, javascript:, data: and anything without a host. */
    public static boolean httpsLink(String link) {
        if (link == null || link.length() > 2048 || !link.startsWith("https://") || link.chars().anyMatch(Character::isWhitespace)) return false;
        try {
            URI uri = URI.create(link);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
