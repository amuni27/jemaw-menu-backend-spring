package com.agafari.com.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * AGF E4-M7 C3 / E4-M8 Q-M8-6: the daily rotation shared by both ad models. The ad at {@link #index} of the servable ads (in
 * their stable order) for a restaurant and its local date; stable across instances and restarts.
 */
public final class AdRotation {

    private AdRotation() {
    }

    public static int index(String businessId, LocalDate day, int size) {
        return Math.floorMod(dailyKey(businessId, day), size);
    }

    /** The first 4 bytes of SHA-256("businessId|yyyy-MM-dd") as an int. */
    static int dailyKey(String businessId, LocalDate day) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((businessId + "|" + day).getBytes(StandardCharsets.UTF_8));
            return ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
