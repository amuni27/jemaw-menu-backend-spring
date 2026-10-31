package com.agafari.com.service;

/** AGF-S2-23: optional Amharic text. Trimmed; blank means "no translation" (stored as null). */
public final class AmharicText {

    private AmharicText() {
    }

    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
