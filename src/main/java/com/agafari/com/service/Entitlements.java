package com.agafari.com.service;

import java.util.Map;

/**
 * AGF E4-M3: what a restaurant may use, as answered by {@link EntitlementService}. {@link #unrestricted()} is exactly
 * today's behaviour (no ads, no limits). {@code degraded} marks a fail-open fallback after an error: an M3 compatibility
 * measure only, not a policy for future paid-feature enforcement (each enforcing story decides its own failure behaviour).
 */
public record Entitlements(Mode mode, String planCode, String status, AdSource adSource, boolean restaurantAdManager,
                           Map<String, Integer> limits, String source, boolean degraded) {

    public enum Mode { INACTIVE, ACTIVE }

    public enum AdSource { NONE, AGAFARI, RESTAURANT }

    public static Entitlements unrestricted() {
        return new Entitlements(Mode.INACTIVE, null, null, AdSource.NONE, false, Map.of(), null, false);
    }

    static Entitlements degradedFallback() {
        return new Entitlements(Mode.INACTIVE, null, null, AdSource.NONE, false, Map.of(), null, true);
    }
}
