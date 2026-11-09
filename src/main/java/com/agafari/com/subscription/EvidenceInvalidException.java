package com.agafari.com.subscription;

/** AGF SUB-R4-09 (DD §2.4): payment evidence was refused. Nothing changes; SUB-R4-10 turns this into a financial exception. */
public class EvidenceInvalidException extends RuntimeException {

    public enum Code { NOT_FOUND, NOT_VERIFIED, OWNER_MISMATCH, AMOUNT_MISMATCH, PLAN_MISMATCH, PERIOD_MISMATCH, ALREADY_CONSUMED }

    private final Code code;

    public EvidenceInvalidException(Code code) {
        super("Payment evidence refused: " + code);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
