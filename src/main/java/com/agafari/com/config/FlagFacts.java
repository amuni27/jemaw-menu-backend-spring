package com.agafari.com.config;

/**
 * AGF SUB-R2-12: what the flag matrix depends on outside the flags. Provider secrets and the legacy-run reports don't exist
 * yet; the stories that add them will report real values here. Prices are reported per market since SUB-R3-01.
 * AGF BE-6 (register §56): the signed publishing dry run is per market (D-3), and {@code ownerPlansReady} (owner plans visible
 * with a monetization configuration, D-5) is reported by the publishing-gate readiness.
 */
public record FlagFacts(boolean postgres, boolean pricesUs, boolean pricesEt, boolean providerSecretsPresent, boolean dryRunSignedOffUs,
                        boolean dryRunSignedOffEt, boolean legacyRunCompleted, boolean unexplainedPublishedCleared, boolean ownerPlansReady) {

    /** The earlier shape (one dry-run fact for both markets; owner plans not part of the matrix then). */
    public FlagFacts(boolean postgres, boolean pricesUs, boolean pricesEt, boolean providerSecretsPresent, boolean dryRunSignedOff,
                     boolean legacyRunCompleted, boolean unexplainedPublishedCleared) {
        this(postgres, pricesUs, pricesEt, providerSecretsPresent, dryRunSignedOff, dryRunSignedOff, legacyRunCompleted, unexplainedPublishedCleared, true);
    }

    public FlagFacts(boolean postgres, boolean pricesAvailable, boolean providerSecretsPresent, boolean dryRunSignedOff,
                     boolean legacyRunCompleted, boolean unexplainedPublishedCleared) {
        this(postgres, pricesAvailable, pricesAvailable, providerSecretsPresent, dryRunSignedOff, legacyRunCompleted, unexplainedPublishedCleared);
    }

    boolean pricesAvailableIn(String market) {
        return "US".equals(market) ? pricesUs : pricesEt;
    }

    boolean dryRunSignedOffIn(String market) {
        return "US".equals(market) ? dryRunSignedOffUs : dryRunSignedOffEt;
    }
}
