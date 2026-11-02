package com.agafari.com.service;

import com.agafari.com.dto.response.GrandfatheringInfo;
import com.agafari.com.dto.response.GrandfatheringPreview;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.enums.MonetizationSetting;
import com.agafari.com.jpa.entity.AdminAuditEvent;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.repository.AdminAuditEventRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.security.AdminAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGF E4-M6: configuration transitions and grandfathering (R7; E4-Q11 Option B; Q1–Q12; D-M6-1…3).
 * <ul>
 *   <li>Grandfathered = a current, non-trial subscription whose plan the current configuration doesn't enable. Computed on
 *   every read and re-checked under the locks before any write; enabling the plan again removes it (Q1, Q5, Q6).</li>
 *   <li>The approved end: the period end when set (authoritative); for an open-ended subscription its transition date, valid
 *   only if a {@code SUBSCRIPTION_TRANSITION_SET} audit record holds that exact date with the configuration version V it was
 *   set under, and no later version enables the plan (D-M6-1). Anything else voids the date (safe).</li>
 *   <li>With {@code app.monetization.transitions-enabled} off (default) all of this is information only. With it on, reads
 *   show the end, writes expire on touch, and the daily job ends due subscriptions at the approved instant, once (Q2, Q9).</li>
 * </ul>
 */
@Slf4j
@Service
public class GrandfatheringService {

    public static final String TRANSITION_SET = "SUBSCRIPTION_TRANSITION_SET";
    public static final String TRANSITION_CLEARED = "SUBSCRIPTION_TRANSITION_CLEARED";
    public static final String ENDED = "GRANDFATHERING_ENDED";
    public static final String PERIOD_END = "PERIOD_END";
    public static final String TRANSITION_DATE = "TRANSITION_DATE";
    public static final String NONE = "NONE";
    private static final String TARGET = "BUSINESS_SUBSCRIPTION";
    private static final Pattern RECORD = Pattern.compile("config=(\\d+);transition=(\\S+)");

    /** The grandfathering state of one subscription; {@link #NOT} when it isn't grandfathered. */
    public record Assessment(boolean grandfathered, Instant until, String untilSource, boolean transitionDateIgnored) {
        static final Assessment NOT = new Assessment(false, null, null, false);
    }

    /** The plan a restaurant's grandfathered subscription ended on (shown to the owner afterwards). */
    public record EndedPlan(String planCode, Instant endedAt) {
    }

    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final AdminAuditEventRepository events;
    private final AdminAuditService audit;
    private final MonetizationLocks locks;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate newTransaction;
    private volatile boolean enabled;

    public GrandfatheringService(BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs, AdminAuditEventRepository events,
                                 AdminAuditService audit, MonetizationLocks locks, MonetizationClock clock, PlatformTransactionManager transactionManager,
                                 @Value("${app.monetization.transitions-enabled:false}") boolean enabled) {
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.events = events;
        this.audit = audit;
        this.locks = locks;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ------------------------------------------------------------------------------------------------- classification

    public Assessment assess(BusinessSubscription s, MonetizationConfig config) {
        if (s == null || !s.isCurrent() || s.isTrial() || config == null || enables(config, s.getPlanCode())) return Assessment.NOT;
        if (s.getPeriodEnd() != null) return new Assessment(true, s.getPeriodEnd(), PERIOD_END, s.getTransitionDate() != null);
        if (s.getTransitionDate() == null) return new Assessment(true, null, NONE, false);
        return transitionValid(s, config) ? new Assessment(true, s.getTransitionDate(), TRANSITION_DATE, false) : new Assessment(true, null, NONE, true);
    }

    /** D-M6-1: the date counts only if recorded with version V and no version after V (up to the current one) enables the plan. */
    private boolean transitionValid(BusinessSubscription s, MonetizationConfig current) {
        Optional<Integer> version = events.findByActionAndTargetId(TRANSITION_SET, s.getId()).stream()
                .map(e -> recordedVersion(e, s.getTransitionDate())).filter(Objects::nonNull).max(Comparator.naturalOrder());
        if (version.isEmpty() || version.get() > current.getVersion()) return false;
        return configs.findByVersionGreaterThan(version.get()).stream().noneMatch(c -> enables(c, s.getPlanCode()));
    }

    private static Integer recordedVersion(AdminAuditEvent event, Instant transitionDate) {
        Matcher m = event.getDetail() == null ? null : RECORD.matcher(event.getDetail());
        if (m == null || !m.matches()) return null;
        try {
            return Instant.parse(m.group(2)).equals(transitionDate) ? Integer.valueOf(m.group(1)) : null;
        } catch (RuntimeException e) {
            return null; // unreadable: the date doesn't apply
        }
    }

    static String record(int configVersion, Instant transitionDate) {
        return "config=" + configVersion + ";transition=" + transitionDate;
    }

    private static boolean enables(MonetizationConfig config, String planCode) {
        return config.models().contains(MonetizationModel.valueOf(planCode));
    }

    private boolean due(Assessment a, Instant now) {
        return a.grandfathered() && a.until() != null && !a.until().isAfter(now);
    }

    /** Ended for every purpose: only with the flag on (with it off, grandfathering is information only). */
    public boolean effectivelyEnded(BusinessSubscription s, MonetizationConfig config) {
        return enabled && due(assess(s, config), clock.now());
    }

    public GrandfatheringInfo info(BusinessSubscription s, MonetizationConfig config) {
        Assessment a = assess(s, config);
        if (!a.grandfathered()) return null;
        return new GrandfatheringInfo(a.until(), a.untilSource(), a.transitionDateIgnored(), enabled, due(a, clock.now()));
    }

    /** The plan the restaurant's last subscription ended on through grandfathering (or ends on now, before the job). */
    public Optional<EndedPlan> endedPlan(String businessId, MonetizationConfig config) {
        Optional<BusinessSubscription> current = subscriptions.findCurrentByBusinessId(businessId);
        if (current.isPresent()) {
            BusinessSubscription s = current.get();
            return effectivelyEnded(s, config) ? Optional.of(new EndedPlan(s.getPlanCode(), assess(s, config).until())) : Optional.empty();
        }
        return subscriptions.findByBusinessIdOrderByCreatedAtDesc(businessId).stream().filter(s -> !s.isTrial()).findFirst()
                .filter(s -> !events.findByActionAndTargetId(ENDED, s.getId()).isEmpty())
                .map(s -> new EndedPlan(s.getPlanCode(), s.getEndedAt()));
    }

    // ------------------------------------------------------------------------------------------------------ ending

    /**
     * Expire on touch (flag on only), in its own transaction under the configuration and restaurant locks, so the end stays
     * persisted even if the request that touched it is then refused. True if this call ended it.
     */
    public boolean expireDueOnTouch(String businessId) {
        if (!enabled) return false;
        Boolean ended = newTransaction.execute(status -> {
            locks.configShared();
            locks.restaurant(businessId);
            MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
            return subscriptions.findCurrentByBusinessId(businessId).map(s -> end(s, config, "on_touch")).orElse(false);
        });
        return Boolean.TRUE.equals(ended);
    }

    /**
     * The daily job's step (flag on only): ends each grandfathered subscription whose approved end has passed, each in its own
     * transaction, re-checked under the locks. -1 if another instance runs the job; otherwise the number ended.
     */
    public int runEnd() {
        if (!enabled) return 0;
        Integer count = transaction.execute(status -> {
            if (!locks.tryTrialJob(TrialService.TRIAL_JOB_LOCK_KEY)) return -1;
            MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
            if (config == null) return 0;
            Instant now = clock.now();
            int ended = 0;
            for (BusinessSubscription candidate : subscriptions.findCurrentNonTrial()) {
                if (!due(assess(candidate, config), now)) continue;
                try {
                    if (Boolean.TRUE.equals(newTransaction.execute(inner -> endWithinLocks(candidate.getId(), candidate.getBusinessId())))) ended++;
                } catch (RuntimeException e) {
                    log.warn("event=grandfathering.end_failed subscriptionId={} errorClass={}", candidate.getId(), e.getClass().getSimpleName());
                }
            }
            return ended;
        });
        return count == null ? 0 : count;
    }

    private boolean endWithinLocks(String subscriptionId, String businessId) {
        locks.configShared();
        locks.restaurant(businessId);
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        return subscriptions.findById(subscriptionId).map(s -> end(s, config, "job")).orElse(false);
    }

    /** The caller holds the configuration and restaurant locks. Re-checks, then the conditional end and its single audit row. */
    private boolean end(BusinessSubscription s, MonetizationConfig config, String via) {
        Instant now = clock.now();
        Assessment a = assess(s, config);
        if (!due(a, now)) return false;
        if (subscriptions.endGrandfathered(s.getId(), a.until(), now, s.getRowVersion()) != 1) return false;
        boolean late = a.until().isBefore(now.minus(Duration.ofDays(1)));
        audit.success(ENDED, null, AdminAuditService.SYSTEM, TARGET, s.getId(), s.getPlanCode() + " ACTIVE -> EXPIRED source="
                + (PERIOD_END.equals(a.untilSource()) ? "period_end" : "transition_date") + " config=" + s.getConfigVersion() + " current="
                + config.getVersion() + " endedAt=" + a.until() + " processedAt=" + now + (late ? " processed_late" : "") + " via=" + via);
        log.info("event=grandfathering.ended subscriptionId={} via={} late={}", s.getId(), via, late);
        return true;
    }

    // ----------------------------------------------------------------------------------------------------- preview

    /** What a configuration would grandfather (read-only; the save dialog's preview). */
    public GrandfatheringPreview preview(MonetizationSetting setting, Collection<MonetizationModel> models) {
        List<MonetizationModel> enabledModels = setting == MonetizationSetting.SETTING_1 ? List.of(MonetizationModel.MODEL_1) : List.copyOf(models);
        MonetizationConfig current = configs.findTopByOrderByVersionDesc().orElse(null);
        Instant now = clock.now();
        Map<String, Integer> byPlan = new TreeMap<>();
        int grandfathered = 0, overdue = 0, openEnded = 0, voided = 0;
        for (BusinessSubscription s : subscriptions.findCurrentNonTrial()) {
            if (enabledModels.contains(MonetizationModel.valueOf(s.getPlanCode()))) continue;
            grandfathered++;
            byPlan.merge(s.getPlanCode(), 1, Integer::sum);
            Instant until = s.getPeriodEnd();
            if (until == null && s.getTransitionDate() != null) {
                if (current != null && !enables(current, s.getPlanCode()) && transitionValid(s, current)) until = s.getTransitionDate();
                else voided++;
            }
            if (until == null) openEnded++;
            else if (!until.isAfter(now)) overdue++;
        }
        return new GrandfatheringPreview(grandfathered, byPlan, overdue, openEnded, voided);
    }

    /** Restaurants whose current subscription is grandfathered under the current configuration (the console list filter). */
    public List<String> grandfatheredBusinessIds() {
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        if (config == null) return List.of();
        return subscriptions.findCurrentNonTrial().stream().filter(s -> !enables(config, s.getPlanCode())).map(BusinessSubscription::getBusinessId).toList();
    }
}
