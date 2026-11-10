package com.agafari.com.subscription;

import com.agafari.com.exception.ProviderPeriodManagedByBillingException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * AGF SUB-R2-02: the start of the single writer of {@code business_subscription} (Backend Detailed Design Rev. 2 §2.1).
 * Each command runs in the caller's transaction, requires the configuration and restaurant locks, checks the transition
 * table and writes the row. Validation, messages and audit stay with the calling services. Today this covers
 * Free with Ads self-service and manual records; the remaining writers move here in later stories.
 */
@Service
public class SubscriptionLifecycle {

    static final String PERIOD_ENDED = "SUBSCRIPTION_PERIOD_ENDED";
    static final String PAST_DUE = "SUBSCRIPTION_PAST_DUE";
    static final String PAID_ACTIVATED = "SUBSCRIPTION_PAID_ACTIVATED";
    static final String RENEWED = "SUBSCRIPTION_RENEWED";
    static final String PAID_REPLACED = "SUBSCRIPTION_PAID_REPLACED";
    private static final String TARGET = "BUSINESS_SUBSCRIPTION";

    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationLocks locks;
    private final EffectiveStateEvaluator evaluator;
    private final MonetizationClock clock;
    private final AdminAuditService audit;
    private final EvidenceValidator evidence;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.beans.factory.ObjectProvider<HeldFunding> heldFunding;

    /** Without billing (unit tests): no paid scheduled change is ever resolved. */
    public SubscriptionLifecycle(BusinessSubscriptionRepository subscriptions, MonetizationLocks locks, EffectiveStateEvaluator evaluator,
                                 MonetizationClock clock, AdminAuditService audit, EvidenceValidator evidence) {
        this(subscriptions, locks, evaluator, clock, audit, evidence, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionLifecycle(BusinessSubscriptionRepository subscriptions, MonetizationLocks locks, EffectiveStateEvaluator evaluator,
                                 MonetizationClock clock, AdminAuditService audit, EvidenceValidator evidence, org.springframework.jdbc.core.JdbcTemplate jdbc,
                                 org.springframework.beans.factory.ObjectProvider<HeldFunding> heldFunding) {
        this.jdbc = jdbc;
        this.heldFunding = heldFunding;
        this.subscriptions = subscriptions;
        this.locks = locks;
        this.evaluator = evaluator;
        this.clock = clock;
        this.audit = audit;
        this.evidence = evidence;
    }

    public BusinessSubscription startOwnerFree(String businessId, String planCode, int configVersion, Instant now, Actor owner) {
        locks.assertHeld(businessId);
        materialize(businessId);
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.OWNER_FREE, SubscriptionSource.OWNER);
        return subscriptions.saveAndFlush(BusinessSubscription.owner(businessId, planCode, configVersion, now, owner.userId()));
    }

    public BusinessSubscription recordManual(String businessId, ManualRecord record, Actor admin) {
        locks.assertHeld(businessId);
        materialize(businessId);
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.ADMIN_RECORD, SubscriptionSource.MANUAL);
        return subscriptions.saveAndFlush(BusinessSubscription.manual(businessId, record.planCode(), record.configVersion(), record.periodEnd(),
                record.now(), admin.userId()));
    }

    /** R-4: a PROVIDER billing period moves only with verified financial evidence, never by a Super Admin edit. */
    public void requireAdminPeriodEditable(BusinessSubscription current) {
        if (SubscriptionSource.of(current.getSource()) == SubscriptionSource.PROVIDER) throw new ProviderPeriodManagedByBillingException();
    }

    /**
     * AGF SUB-R2-03: one Super Admin change to the current row, saved once (the row version moves by one even when a
     * single request changes the period and ends the subscription). The caller holds the locks and has validated the request.
     */
    public BusinessSubscription applyAdminChange(BusinessSubscription current, AdminPeriodChange change, Actor admin) {
        locks.assertHeld(current.getBusinessId());
        LifecycleStatus status = LifecycleStatus.of(current.getStatus());
        SubscriptionSource source = SubscriptionSource.of(current.getSource());
        if (change.periodEnd() != null) {
            requireAdminPeriodEditable(current);
            TransitionRules.require(status, status, TransitionReason.ADMIN_PERIOD_CHANGE, source);
        }
        if (change.end()) TransitionRules.require(status, LifecycleStatus.EXPIRED, TransitionReason.ADMIN, source);
        if (change.clearTransitionDate()) current.changeTransitionDate(null, change.now(), admin.userId());
        if (change.periodEnd() != null) current.changePeriodEnd(change.periodEnd(), change.now(), admin.userId());
        if (change.end()) {
            current.end(change.now(), admin.userId());
            current.recordEnding(TransitionReason.ADMIN.name(), null);
        }
        return subscriptions.saveAndFlush(current);
    }

    /** AGF SUB-R2-03: sets ({@code transitionDate} not null) or clears the transition date of a current row. */
    public BusinessSubscription changeTransitionDate(BusinessSubscription current, Instant transitionDate, Instant now, Actor admin) {
        locks.assertHeld(current.getBusinessId());
        current.changeTransitionDate(transitionDate, now, admin.userId());
        return subscriptions.saveAndFlush(current);
    }

    /** AGF SUB-R2-04: creates a Model 1 trial. */
    public BusinessSubscription startTrial(String businessId, int configVersion, Instant now, Instant end, Actor createdBy) {
        locks.assertHeld(businessId);
        materialize(businessId);
        TransitionRules.require(null, LifecycleStatus.TRIAL, TransitionReason.REGISTRATION_TRIAL, SubscriptionSource.TRIAL);
        return subscriptions.saveAndFlush(BusinessSubscription.trial(businessId, configVersion, now, end, createdBy.userId()));
    }

    /**
     * AGF SUB-R2-04: trial conversion, end + new row in the caller's transaction. The trial is flushed first so the
     * one-current index accepts the successor. {@code trialEndedAt} is the earlier of now and the trial's own end.
     */
    public BusinessSubscription replace(BusinessSubscription trial, Instant trialEndedAt, ManualRecord successor, Actor admin) {
        locks.assertHeld(trial.getBusinessId());
        TransitionRules.requireReplacement(LifecycleStatus.of(trial.getStatus()), SubscriptionSource.of(trial.getSource()), TransitionReason.CONVERTED,
                TransitionReason.ADMIN_RECORD, SubscriptionSource.MANUAL);
        // The link must go in with the end (an ended row is frozen), so the successor's id is known before it is saved.
        BusinessSubscription next = BusinessSubscription.manual(trial.getBusinessId(), successor.planCode(), successor.configVersion(), successor.periodEnd(),
                successor.now(), admin.userId());
        trial.endTrial(trialEndedAt, successor.now(), admin.userId());
        trial.recordEnding(TransitionReason.CONVERTED.name(), next.getId());
        subscriptions.saveAndFlush(trial);
        materialize(trial.getBusinessId());
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.ADMIN_RECORD, SubscriptionSource.MANUAL);
        return subscriptions.saveAndFlush(next);
    }

    /** AGF SUB-R4-09: a paid activation. Repeating it with the same evidence returns the same row. */
    public BusinessSubscription activatePaid(String businessId, PaidActivation a) {
        locks.assertHeld(businessId);
        materialize(businessId);
        EvidenceValidator.Result result = evidence.validate(new EvidenceValidator.Claim(a.evidence(), businessId, a.planCode(), a.provider(), a.periodStart(), a.periodEnd(), null));
        if (result.alreadyAppliedTo() != null) return subscriptions.findById(result.alreadyAppliedTo()).orElseThrow();
        if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new ExpectedSubscriptionChangedException();
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.VERIFIED_PAYMENT, SubscriptionSource.PROVIDER);
        BusinessSubscription saved = subscriptions.saveAndFlush(providerRow(businessId, a));
        consume(saved, a.periodStart(), a.periodEnd(), a.evidence());
        audit.success(PAID_ACTIVATED, null, AdminAuditService.SYSTEM, TARGET, saved.getId(), "plan=" + saved.getPlanCode());
        return saved;
    }

    /** AGF SUB-R4-09: a verified renewal. PD-3 is open, so a period can only start where the current one ends. */
    public BusinessSubscription renew(String subscriptionId, PeriodExtension e) {
        BusinessSubscription s = subscriptions.findById(subscriptionId).orElseThrow(() -> new IllegalStateException("No such subscription"));
        locks.assertHeld(s.getBusinessId());
        materialize(s.getBusinessId());
        EvidenceValidator.Result result = evidence.validate(new EvidenceValidator.Claim(e.evidence(), s.getBusinessId(), s.getPlanCode(), s.getProvider(), e.periodStart(), e.periodEnd(), subscriptionId));
        if (result.alreadyAppliedTo() != null) return s;
        LifecycleStatus status = LifecycleStatus.of(s.getStatus());
        TransitionRules.require(status, LifecycleStatus.ACTIVE, TransitionReason.VERIFIED_RENEWAL, SubscriptionSource.of(s.getSource()));
        if (!e.periodStart().equals(s.getPeriodEnd())) throw new EvidenceInvalidException(EvidenceInvalidException.Code.PERIOD_MISMATCH);
        s.renewPeriod(e.periodStart(), e.periodEnd(), e.now(), AdminAuditService.SYSTEM);
        BusinessSubscription saved = subscriptions.saveAndFlush(s);
        consume(saved, e.periodStart(), e.periodEnd(), e.evidence());
        audit.success(RENEWED, null, AdminAuditService.SYSTEM, TARGET, saved.getId(), status + " -> ACTIVE");
        return saved;
    }

    /** AGF SUB-R4-09: a verified renewal failure. Repeating it changes nothing. */
    public BusinessSubscription markPastDue(String subscriptionId, Instant since, Instant now) {
        BusinessSubscription s = subscriptions.findById(subscriptionId).orElseThrow(() -> new IllegalStateException("No such subscription"));
        locks.assertHeld(s.getBusinessId());
        if (LifecycleStatus.of(s.getStatus()) == LifecycleStatus.PAST_DUE) return s;
        TransitionRules.require(LifecycleStatus.of(s.getStatus()), LifecycleStatus.PAST_DUE, TransitionReason.RENEWAL_FAILED, SubscriptionSource.of(s.getSource()));
        s.markPastDue(since, now, AdminAuditService.SYSTEM);
        BusinessSubscription saved = subscriptions.saveAndFlush(s);
        audit.success(PAST_DUE, null, AdminAuditService.SYSTEM, TARGET, saved.getId(), "ACTIVE -> PAST_DUE");
        return saved;
    }

    /** AGF SUB-R4-09 (DD §4.1): end + a new paid row. Everything is checked first, so a refused replacement leaves the current row untouched. */
    public BusinessSubscription replacePaid(String businessId, String expectedCurrentId, PaidActivation a, TransitionReason ending) {
        locks.assertHeld(businessId);
        materialize(businessId);
        EvidenceValidator.Result result = evidence.validate(new EvidenceValidator.Claim(a.evidence(), businessId, a.planCode(), a.provider(), a.periodStart(), a.periodEnd(), null));
        if (result.alreadyAppliedTo() != null) return subscriptions.findById(result.alreadyAppliedTo()).orElseThrow();
        BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        if (current == null || !current.getId().equals(expectedCurrentId)) throw new ExpectedSubscriptionChangedException();
        TransitionRules.requireReplacement(LifecycleStatus.of(current.getStatus()), SubscriptionSource.of(current.getSource()), ending, TransitionReason.VERIFIED_PAYMENT, SubscriptionSource.PROVIDER);
        BusinessSubscription next = providerRow(businessId, a);
        current.endAtEffectiveTime(a.now(), ending.name(), a.now(), AdminAuditService.SYSTEM);
        current.recordEnding(ending.name(), next.getId());
        subscriptions.saveAndFlush(current);
        BusinessSubscription saved = subscriptions.saveAndFlush(next);
        consume(saved, a.periodStart(), a.periodEnd(), a.evidence());
        audit.success(PAID_REPLACED, null, AdminAuditService.SYSTEM, TARGET, saved.getId(), "replaced=" + current.getId() + " reason=" + ending);
        return saved;
    }

    private static BusinessSubscription providerRow(String businessId, PaidActivation a) {
        return BusinessSubscription.provider(businessId, a.planCode(), a.configVersion(), a.priceId(), a.provider(), a.providerSubscriptionRef(), a.graceDays(), a.periodStart(), a.periodEnd(), a.now(), AdminAuditService.SYSTEM);
    }

    private void consume(BusinessSubscription s, Instant start, Instant end, com.agafari.com.billing.VerifiedPaymentRef ref) {
        locks.assertHeld(s.getBusinessId());
        subscriptions.insertPeriod(java.util.UUID.randomUUID().toString(), s.getId(), s.getBusinessId(), start, end, ref.invoiceId(), ref.paymentId());
    }

    /** AGF SUB-R2-04: the existing idempotent trial expiry; true if this call expired it. Needs only the restaurant lock, as before. */
    public boolean expireTrialIfDue(String businessId, String subscriptionId, Instant now) {
        locks.assertRestaurantHeld(businessId);
        TransitionRules.require(LifecycleStatus.TRIAL, LifecycleStatus.EXPIRED, TransitionReason.TRIAL_END, SubscriptionSource.TRIAL);
        return subscriptions.expireIfDue(subscriptionId, now) == 1;
    }

    /** AGF SUB-R2-04: the existing conditional end of a grandfathered subscription; true if this call ended it. */
    public boolean endGrandfathered(BusinessSubscription s, Instant endedAt, Instant now) {
        locks.assertHeld(s.getBusinessId());
        TransitionRules.require(LifecycleStatus.of(s.getStatus()), LifecycleStatus.EXPIRED, TransitionReason.GRANDFATHERED_END, SubscriptionSource.of(s.getSource()));
        return subscriptions.endGrandfathered(s.getId(), endedAt, now, s.getRowVersion()) == 1;
    }

    /** AGF SUB-R2-11: a scheduled cancellation takes effect at {@code effectiveAt}. */
    public BusinessSubscription applyScheduledCancel(BusinessSubscription current, Instant effectiveAt, Instant now) {
        locks.assertHeld(current.getBusinessId());
        TransitionRules.require(LifecycleStatus.of(current.getStatus()), LifecycleStatus.EXPIRED, TransitionReason.CANCELLED, SubscriptionSource.of(current.getSource()));
        current.endAtEffectiveTime(effectiveAt, TransitionReason.CANCELLED.name(), now, AdminAuditService.SYSTEM);
        return subscriptions.saveAndFlush(current);
    }

    /** AGF SUB-R2-11: a scheduled move to a free plan: the row ends and the owner's row starts at {@code effectiveAt}. */
    public BusinessSubscription applyScheduledDowngrade(BusinessSubscription current, String planCode, int configVersion, Instant effectiveAt, Instant now, String ownerId) {
        locks.assertHeld(current.getBusinessId());
        TransitionRules.requireReplacement(LifecycleStatus.of(current.getStatus()), SubscriptionSource.of(current.getSource()), TransitionReason.CHANGED,
                TransitionReason.OWNER_FREE, SubscriptionSource.OWNER);
        BusinessSubscription next = BusinessSubscription.owner(current.getBusinessId(), planCode, configVersion, effectiveAt, ownerId);
        current.endAtEffectiveTime(effectiveAt, TransitionReason.CHANGED.name(), now, AdminAuditService.SYSTEM);
        current.recordEnding(TransitionReason.CHANGED.name(), next.getId());
        subscriptions.saveAndFlush(current);
        return subscriptions.saveAndFlush(next);
    }

    /**
     * AGF SUB-R2-07 (DD §3.3): persists the effective state of the current row, so the one-current index never blocks a
     * legitimate successor. Does nothing with {@code period-end-enabled} off. Trials and grandfathering keep their own
     * expiry paths and audit rows ({@code TrialExpirer}, {@code GrandfatheringService}).
     */
    public void materialize(String businessId) {
        if (!evaluator.isPeriodEndEnabled()) return; // accepted contract: with the flag off this does nothing at all
        // AGF SUB-R4-15 (PO D4, boundary safeguard 1): a due paid downgrade is resolved before anything can expire the row
        resolveDuePaidChange(businessId);
        materializeCurrent(businessId);
        failPaidChangesOfEndedRows(businessId);
    }

    private void materializeCurrent(String businessId) {
        if (!evaluator.isPeriodEndEnabled()) return;
        locks.assertHeld(businessId);
        BusinessSubscription s = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        if (s == null || s.isTrial()) return;
        Instant now = clock.now();
        // AGF SUB-R2-11: a change that can still take effect decides how this row ends
        if (subscriptions.findDueScheduledChange(s.getId(), now).filter(c -> ScheduledChangeRules.stillApplies(c, subscriptions.findLatestConfig(Limit.of(1)).stream().findFirst().orElse(null))).isPresent()) return;
        EffectiveView view = evaluator.view(s, false, now);
        LifecycleStatus stored = LifecycleStatus.of(s.getStatus());
        SubscriptionSource source = SubscriptionSource.of(s.getSource());
        boolean ended = view.reasonCode().equals("PERIOD_END_PASSED") || view.reasonCode().equals("GRACE_ENDED");
        if (ended) {
            TransitionReason reason = stored == LifecycleStatus.GRANDFATHERED ? TransitionReason.GRANDFATHERED_END
                    : source == SubscriptionSource.PROVIDER ? TransitionReason.PAYMENT_FAILED : TransitionReason.PERIOD_END;
            if (source == SubscriptionSource.PROVIDER && stored == LifecycleStatus.ACTIVE) {
                TransitionRules.require(LifecycleStatus.ACTIVE, LifecycleStatus.PAST_DUE, TransitionReason.RENEWAL_FAILED, source); // grace passed unpersisted
                TransitionRules.require(LifecycleStatus.PAST_DUE, LifecycleStatus.EXPIRED, reason, source);
            } else {
                TransitionRules.require(stored, LifecycleStatus.EXPIRED, reason, source);
            }
            s.endAtEffectiveTime(view.graceUntil() != null ? view.graceUntil() : view.periodEnd(), reason.name(), now, AdminAuditService.SYSTEM);
            subscriptions.saveAndFlush(s);
            audit.success(PERIOD_ENDED, null, AdminAuditService.SYSTEM, "BUSINESS_SUBSCRIPTION", s.getId(), stored + " -> EXPIRED reason=" + reason);
        } else if (view.reasonCode().equals("IN_GRACE") && stored == LifecycleStatus.ACTIVE) {
            TransitionRules.require(LifecycleStatus.ACTIVE, LifecycleStatus.PAST_DUE, TransitionReason.RENEWAL_FAILED, source);
            s.markPastDue(view.periodEnd(), now, AdminAuditService.SYSTEM);
            subscriptions.saveAndFlush(s);
            audit.success(PAST_DUE, null, AdminAuditService.SYSTEM, "BUSINESS_SUBSCRIPTION", s.getId(), "ACTIVE -> PAST_DUE");
        }
    }

    // ---- AGF SUB-R4-15: paid scheduled downgrades (TA §4.4; PO D2–D4, P1, P3) ----

    static final String CHANGE_APPLIED = "SCHEDULED_CHANGE_APPLIED";
    static final String CHANGE_FAILED = "SCHEDULED_CHANGE_FAILED";
    static final String CHANGE_AWAITING = "SCHEDULED_CHANGE_AWAITING_PAYMENT";
    private static final String CHANGE_TARGET = "SCHEDULED_CHANGE";

    /**
     * The current row's paid downgrade whose boundary has passed. Funded: its evidence is re-validated under the locks and
     * the target replaces the row exactly at the boundary, consuming the funding once. Refused evidence fails the change and
     * hands its held charge to review. Unfunded: {@code AWAITING_PAYMENT}, which grants nothing; the row then follows its own
     * path (recorded grace only, AD-7), and the change fails when the row ends.
     */
    private void resolveDuePaidChange(String businessId) {
        if (jdbc == null) return;
        locks.assertHeld(businessId);
        BusinessSubscription s = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        if (s == null || s.isTrial()) return;
        Instant now = clock.now();
        java.util.List<java.util.Map<String, Object>> due = jdbc.queryForList("select * from scheduled_change where from_subscription_id = ? and kind = 'DOWNGRADE' "
                + "and target_price_id is not null and status in ('PENDING', 'AWAITING_PAYMENT') and effective_at <= ? for update", s.getId(), java.sql.Timestamp.from(now));
        if (due.isEmpty()) return;
        java.util.Map<String, Object> change = due.get(0);
        String changeId = (String) change.get("id");
        if (change.get("funding_payment_id") == null) {
            if (jdbc.update("update scheduled_change set status = 'AWAITING_PAYMENT' where id = ? and status = 'PENDING'", changeId) == 1) {
                audit.success(CHANGE_AWAITING, null, AdminAuditService.SYSTEM, CHANGE_TARGET, changeId, "boundary passed without a verified payment");
            }
            return;
        }
        String paymentId = (String) change.get("funding_payment_id"), invoiceId = (String) change.get("funding_invoice_id");
        try {
            BusinessSubscription next = applyFundedChange(s, change, heldFunding.getObject().evidence(paymentId, invoiceId), now);
            jdbc.update("update scheduled_change set status = 'APPLIED', applied_subscription_id = ? where id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", next.getId(), changeId);
            audit.success(CHANGE_APPLIED, null, AdminAuditService.SYSTEM, CHANGE_TARGET, changeId, "paid downgrade applied at " + change.get("effective_at") + " -> " + next.getId());
        } catch (EvidenceInvalidException | IllegalTransitionException | ExpectedSubscriptionChangedException e) {
            jdbc.update("update scheduled_change set status = 'FAILED' where id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", changeId);
            audit.success(CHANGE_FAILED, null, AdminAuditService.SYSTEM, CHANGE_TARGET, changeId, "funded downgrade refused: " + e.getMessage());
            heldFunding.getObject().release(changeId, "EVIDENCE_INVALID", e.getMessage());
        }
    }

    /** Replaces {@code s} by the funded target exactly at the boundary. Everything is checked before anything is written. */
    private BusinessSubscription applyFundedChange(BusinessSubscription s, java.util.Map<String, Object> change, com.agafari.com.billing.VerifiedPaymentRef ref, Instant now) {
        locks.assertHeld(s.getBusinessId());
        Instant effectiveAt = ((java.sql.Timestamp) change.get("effective_at")).toInstant();
        Instant periodEnd = jdbc.queryForObject("select period_end from billing_invoice where id = ?", java.sql.Timestamp.class, ref.invoiceId()).toInstant();
        String targetPlan = (String) change.get("target_plan_code"), targetPrice = (String) change.get("target_price_id");
        EvidenceValidator.Result result = evidence.validate(new EvidenceValidator.Claim(ref, s.getBusinessId(), targetPlan, s.getProvider(), effectiveAt, periodEnd, null));
        if (result.alreadyAppliedTo() != null) throw new EvidenceInvalidException(EvidenceInvalidException.Code.ALREADY_CONSUMED);
        TransitionRules.requireReplacement(LifecycleStatus.of(s.getStatus()), SubscriptionSource.of(s.getSource()), TransitionReason.CHANGED, TransitionReason.VERIFIED_PAYMENT,
                SubscriptionSource.PROVIDER);
        int configVersion = subscriptions.findLatestConfig(Limit.of(1)).stream().findFirst().map(c -> c.getVersion()).orElseThrow(ExpectedSubscriptionChangedException::new);
        // The target's contract starts at the boundary, so that is when its row begins, even when processing resumes late
        // (V13: period_end > created_at). AD-7 is open: no grace is recorded.
        BusinessSubscription next = BusinessSubscription.provider(s.getBusinessId(), targetPlan, configVersion, targetPrice, s.getProvider(), null, null, effectiveAt, periodEnd,
                effectiveAt, AdminAuditService.SYSTEM);
        s.endAtEffectiveTime(effectiveAt, TransitionReason.CHANGED.name(), now, AdminAuditService.SYSTEM);
        s.recordEnding(TransitionReason.CHANGED.name(), next.getId());
        subscriptions.saveAndFlush(s);
        BusinessSubscription saved = subscriptions.saveAndFlush(next);
        consume(saved, effectiveAt, periodEnd, ref);
        audit.success(PAID_REPLACED, null, AdminAuditService.SYSTEM, TARGET, saved.getId(), "replaced=" + s.getId() + " reason=CHANGED scheduled");
        return saved;
    }

    /** A paid change whose row has ended without it can't apply any more: FAILED, and a held charge goes to review. */
    private void failPaidChangesOfEndedRows(String businessId) {
        if (jdbc == null) return;
        for (String changeId : jdbc.queryForList("select c.id from scheduled_change c join business_subscription s on s.id = c.from_subscription_id where c.business_id = ? "
                + "and c.target_price_id is not null and c.status in ('PENDING', 'AWAITING_PAYMENT') and s.status = 'EXPIRED' for update of c", String.class, businessId)) {
            if (jdbc.update("update scheduled_change set status = 'FAILED' where id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", changeId) == 1) {
                audit.success(CHANGE_FAILED, null, AdminAuditService.SYSTEM, CHANGE_TARGET, changeId, "the subscription ended without a verified payment");
                heldFunding.getObject().release(changeId, "STATE_CHANGED", "the subscription ended before the change could apply");
            }
        }
    }
}
