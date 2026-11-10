package com.agafari.com.billing;

import com.agafari.com.config.BillingFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * AGF SUB-R4-10 (PO ruling R3): an intentional restriction on real PROVIDER activation, independent of provider secrets or
 * adapter beans. Each entry is a release gate the Product Owner has kept open. While any remains, {@code billing-enabled}
 * is refused at startup and the payment outcome path ignores the flag. Only a separately authorized change may remove one.
 */
public final class ProviderActivationReadiness {

    private static final List<String> OPEN_GATES = List.of(
            "AD-7 grace duration",
            "R7b escalation of an unresolved payment at the retry ceiling (PAYMENT_OUTCOME_PENDING_REVIEW)",
            "Ownership of an exception for an unattributable payment",
            "SUB-R4-11 financial exception handling and payment-under-review block",
            "SUB-R4-13 refund execution",
            "SUB-R4-14 crash-before-TX-B and CREATING recovery",
            "SUB-R4-16 financial concurrency suite",
            "Provider adapter identity validation",
            "Full mutation verification");

    private ProviderActivationReadiness() {
    }

    public static List<String> openGates() {
        return OPEN_GATES;
    }

    public static boolean ready() {
        return OPEN_GATES.isEmpty();
    }

    /** One violation per market whose billing is switched on while activation isn't ready. */
    public static List<String> violations(BillingFlags flags) {
        List<String> found = new ArrayList<>();
        if (ready()) return found;
        String gates = String.join("; ", OPEN_GATES);
        if (flags.us().billingEnabled()) found.add("billing-enabled (US) requires provider activation readiness; open gates: " + gates);
        if (flags.et().billingEnabled()) found.add("billing-enabled (ET) requires provider activation readiness; open gates: " + gates);
        return found;
    }
}
