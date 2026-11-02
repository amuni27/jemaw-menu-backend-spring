package com.agafari.com.exception;

import org.springframework.http.HttpStatus;

/**
 * AGF Self-Service Phase A: why an owner can't start a plan themselves (or must use self-service instead of a request).
 * Owner-facing wording; {@code auditReason} is the reason code written to the audit log.
 */
public class SelfServiceRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String auditReason;

    private SelfServiceRefusedException(HttpStatus status, String code, String auditReason, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.auditReason = auditReason;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String auditReason() { return auditReason; }

    public static SelfServiceRefusedException disabled() {
        return new SelfServiceRefusedException(HttpStatus.FORBIDDEN, "SELF_SERVICE_DISABLED", "disabled", "Starting a plan yourself isn't available.");
    }
    public static SelfServiceRefusedException unverified() {
        return new SelfServiceRefusedException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "unverified", "Verify your email to start Free with Ads.");
    }
    public static SelfServiceRefusedException notConfigured() {
        return new SelfServiceRefusedException(HttpStatus.BAD_REQUEST, "MONETIZATION_NOT_CONFIGURED", "not_configured", "Plans aren't available yet.");
    }
    public static SelfServiceRefusedException setting1() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "SETTING_NOT_SELF_SERVICE", "setting1", "Plans can't be started here. The Agafari team manages your plan.");
    }
    public static SelfServiceRefusedException versionConflict() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "version_conflict", "The available plans changed. Reload and try again.");
    }
    public static SelfServiceRefusedException notEnabled() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "PLAN_NOT_ENABLED", "not_enabled", "This plan isn't available for your restaurant.");
    }
    public static SelfServiceRefusedException requiresPayment() {
        return new SelfServiceRefusedException(HttpStatus.BAD_REQUEST, "PLAN_REQUIRES_PAYMENT", "requires_payment", "This plan can't be started here. Request it instead.");
    }
    public static SelfServiceRefusedException exists() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "SUBSCRIPTION_EXISTS", "exists", "Your restaurant already has a plan.");
    }
    public static SelfServiceRefusedException pending() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "PLAN_REQUEST_PENDING", "pending",
                "You have a pending plan request. Confirm that it should be withdrawn to start Free with Ads.");
    }
    public static SelfServiceRefusedException requestChanged() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "PLAN_REQUEST_CHANGED", "request_changed", "Your plan request changed. Reload and try again.");
    }
    public static SelfServiceRefusedException useSelfService() {
        return new SelfServiceRefusedException(HttpStatus.CONFLICT, "USE_SELF_SERVICE", "use_self_service", "Start Free with Ads yourself instead of requesting it.");
    }
}
