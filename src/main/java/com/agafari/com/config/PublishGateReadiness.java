package com.agafari.com.config;

import com.agafari.com.billing.Market;

/**
 * AGF BE-6 (register §56): the facts the flag matrix needs before {@code publish-requires-plan} may run in a market: a valid
 * signed dry run for that market (D-3) and owner plans visible with a monetization configuration (D-5). Absent (no bean, e.g.
 * a minimal startup context) means neither, so the gate stays refused.
 */
public interface PublishGateReadiness {

    boolean dryRunSignedOff(Market market);

    boolean ownerPlansReady();
}
