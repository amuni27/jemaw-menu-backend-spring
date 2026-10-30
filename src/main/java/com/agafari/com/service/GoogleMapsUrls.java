package com.agafari.com.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * AGF-S2-12: the only accepted restaurant location links (D-S2-12-2/-3, D-REV-4). https with one of five exact hosts
 * and its path rule; no user-info; no port other than 443; at most 2,048 characters; no whitespace or control
 * characters. Normalising trims, lower-cases the scheme and host and drops an explicit :443 (D-S2-12-P2); path,
 * query and fragment are kept as submitted. Links are only parsed, never fetched or followed.
 */
public final class GoogleMapsUrls {

    public static final int MAX_LENGTH = 2048;

    private GoogleMapsUrls() {
    }

    /** Null or blank: "no link". */
    public static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    /** The normalised link, or empty when it isn't an accepted Google Maps link. */
    public static Optional<String> normalize(String raw) {
        if (isBlank(raw)) return Optional.empty();
        String value = raw.strip();
        if (value.length() > MAX_LENGTH || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.isOpaque()) return Optional.empty();
        if (uri.getRawUserInfo() != null || uri.getHost() == null) return Optional.empty();
        if (uri.getPort() != -1 && uri.getPort() != 443) return Optional.empty();

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!pathAllowed(host, path)) return Optional.empty();

        // rebuild from the raw parts so the path, query and fragment stay exactly as submitted
        StringBuilder out = new StringBuilder("https://").append(host).append(path);
        if (uri.getRawQuery() != null) out.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) out.append('#').append(uri.getRawFragment());
        return Optional.of(out.toString());
    }

    private static boolean pathAllowed(String host, String path) {
        return switch (host) {
            case "www.google.com", "google.com" -> path.equals("/maps") || path.startsWith("/maps/") || path.startsWith("/maps?");
            case "maps.google.com" -> true;
            case "maps.app.goo.gl" -> path.length() > 1;
            case "goo.gl" -> path.startsWith("/maps/") && path.length() > "/maps/".length();
            default -> false;
        };
    }
}
