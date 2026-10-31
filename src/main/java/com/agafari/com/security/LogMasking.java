package com.agafari.com.security;

/** Log-safe forms of personal data (AGF-S1-16): logs must never contain a full email address. */
public final class LogMasking {

    private LogMasking() {
    }

    /** {@code owner@example.com} → {@code o***@example.com}; the domain stays for troubleshooting. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0) return "***";
        return trimmed.charAt(0) + "***" + trimmed.substring(at);
    }
}
