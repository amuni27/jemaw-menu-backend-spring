package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** AGF BE-6 (register §56): the publishing-gate dry run, as Super Admins see it. */
public final class PublishDryRunView {

    private PublishDryRunView() {
    }

    public enum Section { EXEMPT, ALLOWED, BLOCKED, PUBLISHED_WITHOUT_PLAN, REVIEW, NOT_GATED }

    public record Config(int version, String setting, List<String> models) {
    }

    public record Counts(int exempt, int allowed, int blocked, int errors, int total, int publishedWithoutPlan, int review, int notGated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignOff(Instant signedAt, String by, String cutOver, String fingerprint, boolean valid) {
    }

    /** {@code blockers}: CUT_OVER_NOT_CONFIGURED, MONETIZATION_NOT_CONFIGURED, OWNER_PLANS_HIDDEN (any → no sign-off). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String market, Instant generatedAt, String cutOver, Config config, boolean ownerPlansVisible, boolean trialsEnabled,
                          boolean selfServiceEnabled, boolean periodEndEnabled, boolean gateEnabled, boolean billingEnabled, List<String> blockers,
                          Counts counts, String fingerprint, SignOff latestSignOff) {
    }

    /**
     * One restaurant (sections EXEMPT, ALLOWED, BLOCKED, REVIEW, NOT_GATED) or one menu (PUBLISHED_WITHOUT_PLAN: the menu fields
     * and {@code restaurantSection}). Only the fields that apply are present.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(String businessId, String name, String country, Instant createdAt, String ownerStatus, Boolean emailVerified,
                      Integer publishedMenus, String plan, String reason, Boolean pendingRequest, Boolean openCheckout, Boolean paymentUnderReview,
                      List<String> flags, String menuId, String menuName, String restaurantSection) {
    }

    public record Page(Section section, List<Row> items, int page, int size, long total) {
    }

    public record SignOffRequest(String market, String fingerprint) {
    }

    public record SignOffResponse(String market, Instant signedAt, String cutOver, String fingerprint) {
    }
}
