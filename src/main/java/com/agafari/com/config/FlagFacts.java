package com.agafari.com.config;

/**
 * AGF SUB-R2-12: what the flag matrix depends on outside the flags. Provider secrets and the legacy-run reports don't exist
 * yet; the stories that add them will report real values here. Prices are reported per market since SUB-R3-01.
 */
public record FlagFacts(boolean postgres, boolean pricesUs, boolean pricesEt, boolean providerSecretsPresent, boolean dryRunSignedOff,
                        boolean legacyRunCompleted, boolean unexplainedPublishedCleared) {

    public FlagFacts(boolean postgres, boolean pricesAvailable, boolean providerSecretsPresent, boolean dryRunSignedOff,
                     boolean legacyRunCompleted, boolean unexplainedPublishedCleared) {
        this(postgres, pricesAvailable, pricesAvailable, providerSecretsPresent, dryRunSignedOff, legacyRunCompleted, unexplainedPublishedCleared);
    }

    boolean pricesAvailableIn(String market) {
        return "US".equals(market) ? pricesUs : pricesEt;
    }
}
