package com.agafari.com.service;

import com.agafari.com.dto.request.TrialStartRequest;
import com.agafari.com.dto.response.SubscriptionActivationResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.OwnerTrialRefusedException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AGF BE-1 (register §45 D-3, §46 Q1/Q2): the owner starts the Model 1 free trial themselves ({@code POST /api/plan/trial}).
 * Offered only while {@code app.monetization.trials-enabled} and the owner plan view are on. Email verification is required
 * only while it is operational (Q2, the same rule as SS-2). The restaurant and the owner come from the session (the
 * controller), never from the request. Eligibility, locking, creation and the pending-intent resolution are
 * {@link TrialService#startForOwner}. Every refusal is audited as {@link TrialService#REJECTED} with a reason.
 * Registration trials and the admin start are unchanged.
 */
@Service
public class OwnerTrialService {

    private static final String TARGET = "BUSINESS_SUBSCRIPTION";

    private final TrialService trials;
    private final PlanRequestService planRequests;
    private final AdminAuditService audit;

    public OwnerTrialService(TrialService trials, PlanRequestService planRequests, AdminAuditService audit) {
        this.trials = trials;
        this.planRequests = planRequests;
        this.audit = audit;
    }

    public SubscriptionActivationResponse start(String businessId, TrialStartRequest request, String ownerId, String ownerEmail) {
        String actor = LogMasking.maskEmail(ownerEmail);
        try {
            if (!trials.isEnabled() || !planRequests.isOwnerPlansVisible()) throw OwnerTrialRefusedException.disabled();
            if (request == null || request.expectedConfigVersion() == null) {
                throw new FieldValidationException(Map.of("expectedConfigVersion", "Reload and try again"));
            }
            if (planRequests.verificationRequired(businessId)) throw OwnerTrialRefusedException.unverified();
            return trials.startForOwner(businessId, request.expectedConfigVersion(), ownerId, actor);
        } catch (OwnerTrialRefusedException e) {
            audit.failure(TrialService.REJECTED, ownerId, actor, TARGET, businessId, e.auditReason());
            throw e;
        } catch (FieldValidationException e) {
            audit.failure(TrialService.REJECTED, ownerId, actor, TARGET, businessId, "invalid");
            throw e;
        }
    }
}
