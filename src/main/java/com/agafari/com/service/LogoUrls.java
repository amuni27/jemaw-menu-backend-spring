package com.agafari.com.service;

/** Public URL of a stored business logo (AGF-S2-01). */
public final class LogoUrls {

    private LogoUrls() {
    }

    /** The public URL for {@code logoKey}, or null when the business has no logo. */
    public static String publicUrl(String publicBaseUrl, String logoKey) {
        if (logoKey == null || logoKey.isBlank()) return null;
        return publicBaseUrl.replaceAll("/$", "") + "/" + logoKey.replaceAll("^/", "");
    }
}
