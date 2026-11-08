package com.agafari.com.config;

/**
 * AGF SUB-R2-12: what the flag matrix depends on outside the flags. Prices, provider secrets and the legacy-run reports
 * don't exist yet; the stories that add them will report real values here.
 */
public record FlagFacts(boolean postgres, boolean pricesAvailable, boolean providerSecretsPresent, boolean dryRunSignedOff,
                        boolean legacyRunCompleted, boolean unexplainedPublishedCleared) {
}
