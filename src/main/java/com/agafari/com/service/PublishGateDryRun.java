package com.agafari.com.service;

import com.agafari.com.billing.Market;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.PublishGateReadiness;
import com.agafari.com.dto.response.PublishDryRunView;
import com.agafari.com.dto.response.PublishDryRunView.Counts;
import com.agafari.com.dto.response.PublishDryRunView.Row;
import com.agafari.com.dto.response.PublishDryRunView.Section;
import com.agafari.com.dto.response.PublishDryRunView.SignOff;
import com.agafari.com.dto.response.PublishDryRunView.Summary;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.PublishDryRunRefusedException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.ScheduledChange;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AGF BE-6 (register §56): the read-only publishing-gate dry run per market, its sign-off, and the readiness facts the flag
 * matrix reads. Every restaurant of the market is classified by {@link PublishEligibility#evaluate}, the gate's own rule, "as if
 * the gate were on" (D-1: B-1 governs): EXEMPT, ALLOWED, BLOCKED (or an evaluation error). Also: published menus of restaurants
 * without an entitled plan (policy 7: they stay published), items for manual review (D-7: report only), and restaurants outside
 * US/ET (B-2: not gated). Reads in set queries per market; nothing is written except the sign-off audit event (D-3).
 */
@Slf4j
@Component
public class PublishGateDryRun implements PublishGateReadiness {

    public static final String SIGNED_OFF = "PUBLISH_DRY_RUN_SIGNED_OFF";
    static final Duration VALIDITY = Duration.ofDays(14); // D-3
    private static final int CHUNK = 500;
    // PaymentReview's rule for an open review (SUB-R4-11), in set form; billing tables exist on PostgreSQL only
    private static final String OPEN_REVIEW = "involves_customer_funds and status in ('AUTO_HANDLING', 'OPEN', 'IN_REVIEW', 'ESCALATED')";

    private final JdbcTemplate jdbc;
    private final PublishEligibility eligibility;
    private final RestaurantCutOver cutOver;
    private final PlanRequestService planRequests;
    private final TrialService trials;
    private final EffectiveStateEvaluator evaluator;
    private final BillingFlags flags;
    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final MonetizationClock clock;
    private final AdminAuditService audit;
    private volatile Boolean postgres;

    public PublishGateDryRun(JdbcTemplate jdbc, PublishEligibility eligibility, RestaurantCutOver cutOver, PlanRequestService planRequests, TrialService trials,
                             EffectiveStateEvaluator evaluator, BillingFlags flags, BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs,
                             MonetizationClock clock, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.eligibility = eligibility;
        this.cutOver = cutOver;
        this.planRequests = planRequests;
        this.trials = trials;
        this.evaluator = evaluator;
        this.flags = flags;
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.clock = clock;
        this.audit = audit;
    }

    /** The whole report for one market: the summary and every section's rows. */
    public record Report(Summary summary, Map<Section, List<Row>> sections) {
    }

    public static Market market(String value) {
        return Market.fromCountry(value == null ? null : value.trim().toUpperCase()).orElseThrow(() -> new BadRequestException("market must be US or ET"));
    }

    // ------------------------------------------------------------------------------------------------ report

    public Report report(Market market) {
        Instant now = clock.now();
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        Instant from = cutOver.from();

        List<Map<String, Object>> restaurants = jdbc.queryForList("select b.id, b.name, b.country, b.\"createdAt\" as created_at, u.status as owner_status, "
                + "u.email_verified_at from business b left join users u on u.id = b.owner_user_id where b.country = ? order by b.id", market.name());
        List<String> ids = restaurants.stream().map(r -> (String) r.get("id")).toList();

        Map<String, List<Map<String, Object>>> published = group(jdbc.queryForList("select m.id, m.name, m.business_id from menu m join business b on b.id = m.business_id "
                + "where b.country = ? and m.is_active and m.visibility = 'PUBLIC' order by m.id", market.name()));
        Set<String> activePrivate = ids(jdbc.queryForList("select distinct m.business_id from menu m join business b on b.id = m.business_id "
                + "where b.country = ? and m.is_active and m.visibility = 'PRIVATE'", String.class, market.name()));
        Set<String> pendingRequests = ids(jdbc.queryForList("select distinct r.business_id from plan_request r join business b on b.id = r.business_id "
                + "where b.country = ? and r.status = 'PENDING'", String.class, market.name()));
        Set<String> anyRow = ids(jdbc.queryForList("select distinct s.business_id from business_subscription s join business b on b.id = s.business_id "
                + "where b.country = ?", String.class, market.name()));
        Set<String> legacyIntents = ids(jdbc.queryForList("select distinct i.business_id from trial_intent i join business b on b.id = i.business_id "
                + "where b.country = ? and i.status = 'PENDING'", String.class, market.name()));
        Set<String> openCheckouts = onPostgres() ? ids(jdbc.queryForList("select distinct c.business_id from checkout_session c join business b on b.id = c.business_id "
                + "where b.country = ? and c.status in ('CREATING', 'OPEN', 'CANCEL_REQUESTED')", String.class, market.name())) : Set.of();
        Set<String> reviews = onPostgres() ? ids(jdbc.queryForList("select distinct e.business_id from billing_exception e join business b on b.id = e.business_id "
                + "where b.country = ? and " + OPEN_REVIEW.replace("involves_customer_funds", "e.involves_customer_funds").replace("status in", "e.status in"),
                String.class, market.name())) : Set.of();

        Map<String, BusinessSubscription> current = new HashMap<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            subscriptions.findCurrentByBusinessIds(ids.subList(i, Math.min(ids.size(), i + CHUNK))).forEach(s -> current.put(s.getBusinessId(), s));
        }
        Map<String, ScheduledChange> due = new HashMap<>();
        List<String> rowIds = current.values().stream().map(BusinessSubscription::getId).toList();
        for (int i = 0; i < rowIds.size(); i += CHUNK) {
            subscriptions.findDueScheduledChanges(rowIds.subList(i, Math.min(rowIds.size(), i + CHUNK)), now).forEach(c -> due.put(c.getFromSubscriptionId(), c));
        }

        Map<Section, List<Row>> sections = new HashMap<>();
        for (Section s : Section.values()) sections.put(s, new ArrayList<>());
        Map<String, Section> sectionOf = new HashMap<>();
        int errors = 0;
        for (Map<String, Object> r : restaurants) {
            String id = (String) r.get("id");
            Instant createdAt = instant(r.get("created_at"));
            BusinessSubscription row = current.get(id);
            List<String> reviewFlags = new ArrayList<>();
            PublishEligibility.Evaluation evaluation;
            try {
                evaluation = eligibility.evaluate(createdAt, row, row == null ? null : due.get(row.getId()), config, anyRow.contains(id), now);
            } catch (RuntimeException e) {
                log.warn("event=publish_dry_run.evaluation_failed businessId={} error={}", id, e.getClass().getSimpleName());
                evaluation = null;
                reviewFlags.add("EVALUATION_ERROR");
                errors++;
            }
            int menus = published.getOrDefault(id, List.of()).size();
            String plan = row == null ? null : row.getPlanCode() + " " + row.getStatus();
            if (evaluation != null) {
                Section section = switch (evaluation.decision()) {
                    case EXEMPT -> Section.EXEMPT;
                    case ENTITLED -> Section.ALLOWED;
                    default -> Section.BLOCKED;
                };
                sectionOf.put(id, section);
                boolean blocked = section == Section.BLOCKED;
                sections.get(section).add(new Row(id, (String) r.get("name"), (String) r.get("country"), createdAt, (String) r.get("owner_status"),
                        r.get("email_verified_at") != null, menus, plan, section == Section.EXEMPT ? null : evaluation.reason(),
                        blocked ? pendingRequests.contains(id) : null, blocked ? openCheckouts.contains(id) : null, blocked ? reviews.contains(id) : null,
                        null, null, null, null));
                if (evaluation.entitledOnlyBecausePeriodEndOff()) reviewFlags.add("ENTITLED_ONLY_BECAUSE_PERIOD_END_OFF");
            }
            if (createdAt == null) reviewFlags.add("CREATED_AT_UNKNOWN");
            if (r.get("owner_status") == null) reviewFlags.add("OWNER_MISSING");
            else if (!"ACTIVE".equals(r.get("owner_status"))) reviewFlags.add("OWNER_INACTIVE");
            if (row != null && !row.isTrial() && row.getPeriodEnd() == null && !row.isOpenEnded()) reviewFlags.add("PERIOD_END_MISSING");
            if (legacyIntents.contains(id)) reviewFlags.add("LEGACY_TRIAL_INTENT");
            if (activePrivate.contains(id)) reviewFlags.add("ACTIVE_PRIVATE_MENU");
            if (!reviewFlags.isEmpty()) {
                sections.get(Section.REVIEW).add(new Row(id, (String) r.get("name"), (String) r.get("country"), createdAt, (String) r.get("owner_status"),
                        r.get("email_verified_at") != null, menus, plan, null, null, null, null, List.copyOf(reviewFlags), null, null, null));
            }
        }
        // policy 7: listed, never changed
        for (Map.Entry<String, List<Map<String, Object>>> e : published.entrySet()) {
            Section section = sectionOf.get(e.getKey());
            if (section == Section.ALLOWED) continue;
            String name = restaurants.stream().filter(r -> e.getKey().equals(r.get("id"))).map(r -> (String) r.get("name")).findFirst().orElse(null);
            for (Map<String, Object> m : e.getValue()) {
                sections.get(Section.PUBLISHED_WITHOUT_PLAN).add(new Row(e.getKey(), name, market.name(), null, null, null, null, null, null, null, null, null, null,
                        (String) m.get("id"), (String) m.get("name"), section == null ? "EVALUATION_ERROR" : section.name()));
            }
        }
        // B-2: not gated in any market
        jdbc.queryForList("select b.id, b.name, b.country, b.\"createdAt\" as created_at from business b where b.country not in ('US', 'ET') order by b.id")
                .forEach(r -> sections.get(Section.NOT_GATED).add(new Row((String) r.get("id"), (String) r.get("name"), (String) r.get("country"),
                        instant(r.get("created_at")), null, null, null, null, "NOT_GATED", null, null, null, null, null, null, null)));

        List<String> blockers = new ArrayList<>();
        if (from == null) blockers.add("CUT_OVER_NOT_CONFIGURED");
        if (config == null) blockers.add("MONETIZATION_NOT_CONFIGURED");
        if (!planRequests.isOwnerPlansVisible()) blockers.add("OWNER_PLANS_HIDDEN");
        Counts counts = new Counts(sections.get(Section.EXEMPT).size(), sections.get(Section.ALLOWED).size(), sections.get(Section.BLOCKED).size(), errors,
                restaurants.size(), sections.get(Section.PUBLISHED_WITHOUT_PLAN).size(), sections.get(Section.REVIEW).size(), sections.get(Section.NOT_GATED).size());
        String cut = from == null ? null : from.toString();
        Summary summary = new Summary(market.name(), now, cut,
                config == null ? null : new PublishDryRunView.Config(config.getVersion(), config.getSetting().name(), config.models().stream().map(Enum::name).toList()),
                planRequests.isOwnerPlansVisible(), trials.isEnabled(), planRequests.isSelfServiceEnabled(), evaluator.isPeriodEndEnabled(),
                eligibility.gateOn(market), marketFlags(market).billingEnabled(), List.copyOf(blockers), counts,
                fingerprint(market, cut, config, sections), latestSignOff(market).orElse(null));
        return new Report(summary, sections);
    }

    public PublishDryRunView.Page page(Market market, Section section, int page, int size) {
        List<Row> rows = report(market).sections().get(section);
        int fromIndex = Math.min(rows.size(), page * size);
        return new PublishDryRunView.Page(section, rows.subList(fromIndex, Math.min(rows.size(), fromIndex + size)), page, size, rows.size());
    }

    public static final String CSV_HEADER = "section,businessId,name,country,createdAt,ownerStatus,emailVerified,publishedMenus,plan,reason,menuId,menuName,flags";

    public String csv(Market market) {
        Report report = report(market);
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Section section : List.of(Section.EXEMPT, Section.ALLOWED, Section.BLOCKED, Section.PUBLISHED_WITHOUT_PLAN, Section.REVIEW, Section.NOT_GATED)) {
            for (Row r : report.sections().get(section)) {
                out.append(String.join(",", section.name(), csv(r.businessId()), csv(r.name()), csv(r.country()), csv(r.createdAt()), csv(r.ownerStatus()),
                        csv(r.emailVerified()), csv(r.publishedMenus()), csv(r.plan()), csv(r.reason()), csv(r.menuId()), csv(r.menuName()),
                        csv(r.flags() == null ? null : String.join(" ", r.flags())))).append('\n');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ sign-off (D-3)

    public PublishDryRunView.SignOffResponse signOff(Market market, String fingerprint, String adminId, String adminLabel) {
        Summary summary = report(market).summary();
        if (!summary.blockers().isEmpty()) {
            throw new PublishDryRunRefusedException(PublishDryRunRefusedException.BLOCKED, "The dry run has blockers: " + String.join(", ", summary.blockers()));
        }
        if (fingerprint == null || !fingerprint.equals(summary.fingerprint())) {
            throw new PublishDryRunRefusedException(PublishDryRunRefusedException.CHANGED, "The report changed since you viewed it. Review it again before signing off.");
        }
        Counts c = summary.counts();
        audit.success(SIGNED_OFF, adminId, adminLabel, "MARKET", market.name(), "cutOver=" + summary.cutOver() + " configVersion=" + summary.config().version()
                + " exempt=" + c.exempt() + " allowed=" + c.allowed() + " blocked=" + c.blocked() + " errors=" + c.errors() + " publishedWithoutPlan="
                + c.publishedWithoutPlan() + " review=" + c.review() + " notGated=" + c.notGated() + " fingerprint=" + summary.fingerprint());
        log.info("event=publish_dry_run.signed_off market={} by={}", market, adminId);
        return new PublishDryRunView.SignOffResponse(market.name(), latestSignOff(market).map(SignOff::signedAt).orElse(clock.now()), summary.cutOver(), summary.fingerprint());
    }

    private java.util.Optional<SignOff> latestSignOff(Market market) {
        List<Map<String, Object>> rows = jdbc.queryForList("select occurred_at, actor_label, detail from admin_audit_event where action = ? and target_type = 'MARKET' "
                + "and target_id = ? and outcome = 'SUCCESS' order by occurred_at desc limit 1", SIGNED_OFF, market.name());
        if (rows.isEmpty()) return java.util.Optional.empty();
        Map<String, Object> r = rows.get(0);
        String detail = (String) r.get("detail");
        String signedCutOver = field(detail, "cutOver");
        Instant signedAt = instant(r.get("occurred_at"));
        Instant from = cutOver.from();
        boolean valid = from != null && from.toString().equals(signedCutOver) && signedAt != null && !signedAt.isBefore(clock.now().minus(VALIDITY));
        return java.util.Optional.of(new SignOff(signedAt, (String) r.get("actor_label"), signedCutOver, field(detail, "fingerprint"), valid));
    }

    // ------------------------------------------------------------------------------------------------ readiness (flag matrix)

    @Override
    public boolean dryRunSignedOff(Market market) {
        return latestSignOff(market).map(SignOff::valid).orElse(false);
    }

    @Override
    public boolean ownerPlansReady() {
        return planRequests.isOwnerPlansVisible() && configs.findTopByOrderByVersionDesc().isPresent();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private BillingFlags.MarketFlags marketFlags(Market market) {
        return market == Market.US ? flags.us() : flags.et();
    }

    private static String fingerprint(Market market, String cut, MonetizationConfig config, Map<Section, List<Row>> sections) {
        List<String> lines = new ArrayList<>();
        lines.add("market=" + market + " cutOver=" + cut + " config=" + (config == null ? null : config.getVersion()));
        sections.forEach((section, rows) -> rows.forEach(r -> lines.add(section + "|" + r.businessId() + "|" + r.reason() + "|" + r.menuId() + "|" + r.flags())));
        lines.sort(Comparator.naturalOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String field(String detail, String name) {
        if (detail == null) return null;
        for (String part : detail.split(" ")) if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        return null;
    }

    private static Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> menus) {
        return menus.stream().collect(Collectors.groupingBy(m -> (String) m.get("business_id"), HashMap::new, Collectors.toList()));
    }

    private static Set<String> ids(List<String> values) {
        return new HashSet<>(values);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = value.toString();
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private boolean onPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
            postgres = known;
        }
        return known;
    }
}
