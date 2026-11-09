package com.agafari.com.billing.port;

/** The approved checkout kinds (TA §3.2). */
public enum CheckoutKind {
    NEW, CONVERSION, UPGRADE, RENEWAL, DOWNGRADE_RENEWAL
}
