package com.agafari.com.billing.port;

import java.util.Map;

/** AGF SUB-R4-07 (BD-20): during a secret rotation both the current and the previous secret are accepted. */
public final class RotatingSignatureVerifier implements SignatureVerifier {

    private final SignatureVerifier current;
    private final SignatureVerifier previous;

    /** @param previous null when no rotation is in progress */
    public RotatingSignatureVerifier(SignatureVerifier current, SignatureVerifier previous) {
        if (current == null) throw new IllegalArgumentException("A current verifier is required");
        this.current = current;
        this.previous = previous;
    }

    @Override
    public boolean verify(Map<String, String> headers, byte[] rawBody) {
        return current.verify(headers, rawBody) || (previous != null && previous.verify(headers, rawBody));
    }

    @Override
    public InboundEvent identify(byte[] rawBody) {
        return current.identify(rawBody);
    }
}
