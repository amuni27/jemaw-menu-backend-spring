package com.agafari.com.exception;

import org.springframework.http.HttpStatus;

/**
 * AGF BE-1: why an owner can't start the Model 1 free trial themselves ({@code POST /api/plan/trial}). Owner-facing
 * wording; {@code auditReason} is the reason code written to the audit log (the admin start's codes where they match).
 */
public class OwnerTrialRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String auditReason;

    private OwnerTrialRefusedException(HttpStatus status, String code, String auditReason, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.auditReason = auditReason;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String auditReason() { return auditReason; }

    public static OwnerTrialRefusedException disabled() {
        return new OwnerTrialRefusedException(HttpStatus.FORBIDDEN, "TRIALS_DISABLED", "disabled", "Free trials aren't available.");
    }
    public static OwnerTrialRefusedException unverified() {
        return new OwnerTrialRefusedException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "unverified", "Verify your email to start your free trial.");
    }
    public static OwnerTrialRefusedException notConfigured() {
        return new OwnerTrialRefusedException(HttpStatus.BAD_REQUEST, "MONETIZATION_NOT_CONFIGURED", "not_configured", "Plans aren't available yet.");
    }
    public static OwnerTrialRefusedException notOffered() {
        return new OwnerTrialRefusedException(HttpStatus.CONFLICT, "TRIAL_NOT_OFFERED", "plan_not_enabled", "A free trial isn't offered for your restaurant.");
    }
    public static OwnerTrialRefusedException versionConflict() {
        return new OwnerTrialRefusedException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "version_conflict", "The available plans changed. Reload and try again.");
    }
    public static OwnerTrialRefusedException exists() {
        return new OwnerTrialRefusedException(HttpStatus.CONFLICT, "SUBSCRIPTION_EXISTS", "subscription_exists", "Your restaurant already has a plan.");
    }
    public static OwnerTrialRefusedException pending() {
        return new OwnerTrialRefusedException(HttpStatus.CONFLICT, "PLAN_REQUEST_PENDING", "request_pending", "You have a pending plan request.");
    }
    public static OwnerTrialRefusedException used() {
        return new OwnerTrialRefusedException(HttpStatus.CONFLICT, "TRIAL_ALREADY_USED", "trial_used", "Your restaurant has already had its free trial.");
    }
}
