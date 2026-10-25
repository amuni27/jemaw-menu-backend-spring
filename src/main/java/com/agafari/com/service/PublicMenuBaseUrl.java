package com.agafari.com.service;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * AGF-S2-21 (D2): the public guest-menu base URL, from configuration only. Same rules and fallback as the frontend
 * (AGF-S2-20, {@code publicMenuUrl.ts}): absolute https URL (http only for localhost, 127.0.0.1, [::1], *.localhost),
 * no credentials, query, fragment or whitespace; host lower-cased, default port and trailing slashes dropped.
 * Unset keeps the address existing QR codes use. No brand domain is hard-coded.
 */
public final class PublicMenuBaseUrl {

    /** Used only when the setting is unset or blank; the same fallback as the frontend. */
    public static final String FALLBACK = "https://agafari-menu.onrender.com";

    private static final Pattern SCHEME = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private PublicMenuBaseUrl() {
    }

    /** The normalised base, or empty when the configured value is unusable (callers must fail closed). */
    public static Optional<String> normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return Optional.of(FALLBACK);
        if (value.chars().anyMatch(Character::isWhitespace)) return Optional.empty();
        if (!SCHEME.matcher(value).matches() || value.contains("?") || value.contains("#")) return Optional.empty();

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) return Optional.empty();
        host = host.toLowerCase(Locale.ROOT);

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.endsWith(".localhost");
        if (scheme.equals("http") && !local) return Optional.empty();

        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port) + path);
    }
}
