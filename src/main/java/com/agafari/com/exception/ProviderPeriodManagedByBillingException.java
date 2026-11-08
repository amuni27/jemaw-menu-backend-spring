package com.agafari.com.exception;

/** AGF SUB-R2-03 (R-4): a PROVIDER billing period moves only with verified financial evidence, never by a Super Admin edit. */
public class ProviderPeriodManagedByBillingException extends RuntimeException {

    public static final String CODE = "PROVIDER_PERIOD_MANAGED_BY_BILLING";

    public ProviderPeriodManagedByBillingException() {
        super("This billing period is managed by the payment provider and can't be edited here.");
    }
}
