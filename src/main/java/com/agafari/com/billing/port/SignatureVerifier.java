package com.agafari.com.billing.port;

import java.util.Map;

/** AGF SUB-R4-04: verifies a webhook against its raw body (DD §8.1). */
public interface SignatureVerifier {

    boolean verify(Map<String, String> headers, byte[] rawBody);
}
