package com.agafari.com.service;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.Money;
import com.agafari.com.billing.PriceAvailability;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.dto.request.PlanPriceCreateRequest;
import com.agafari.com.dto.response.PlanPriceResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.PlanPrice;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanPriceRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R3-01: price versions. Rows are never changed: activating a version adds a new active version. Nothing here
 * shows a price to an owner; that needs {@code prices-visible}.
 */
@Service
public class PlanPriceService implements PriceAvailability {

    static final String CREATED = "PLAN_PRICE_VERSION_CREATED";
    static final String ACTIVATED = "PLAN_PRICE_ACTIVATED";
    private static final String TARGET = "PLAN_PRICE";

    private final PlanPriceRepository prices;
    private final SubscriptionPlanRepository plans;
    private final MonetizationConfigRepository configs;
    private final AdminAuditService audit;
    private final MonetizationClock clock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PlanPriceService(PlanPriceRepository prices, SubscriptionPlanRepository plans, MonetizationConfigRepository configs, AdminAuditService audit,
                            MonetizationClock clock, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.prices = prices;
        this.plans = plans;
        this.configs = configs;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    public List<PlanPriceResponse> list() {
        return prices.findAllVersions().stream().map(PlanPriceService::response).toList();
    }

    public PlanPriceResponse createVersion(PlanPriceCreateRequest request, String adminId, String email) {
        SubscriptionPlan plan = request.planCode() == null ? null : plans.findById(request.planCode()).orElse(null);
        if (plan == null || !plan.isPaid()) throw invalid("planCode", "Choose a paid plan");
        Market market = parse(Market.class, request.market(), "market");
        PriceInterval interval = parse(PriceInterval.class, request.interval(), "interval");
        Money amount = amount(request.amountMinor(), market);
        Instant now = clock.now();
        Instant validFrom = request.validFrom() == null ? now : request.validFrom();
        return transaction.execute(status -> {
            int version = nextVersion(plan.getCode(), market.name(), interval.name());
            PlanPrice saved = prices.saveAndFlush(PlanPrice.version(plan.getCode(), market.name(), amount.currency().name(), interval.name(), amount.minor(), version, request.providerPriceRef(), false, validFrom));
            audit.success(CREATED, adminId, LogMasking.maskEmail(email), TARGET, saved.getId(), detail(saved));
            return response(saved);
        });
    }

    /** Adds a new active version with the same terms; the version it copies stays as it was. */
    public PlanPriceResponse activate(String id, String adminId, String email) {
        PlanPrice source = prices.findById(id).orElseThrow(() -> new NotFoundException("Price not found"));
        if (source.isActive()) throw invalid("id", "This version is already active");
        return transaction.execute(status -> {
            int version = nextVersion(source.getPlanCode(), source.getMarket(), source.getInterval());
            PlanPrice saved = prices.saveAndFlush(PlanPrice.version(source.getPlanCode(), source.getMarket(), source.getCurrency(), source.getInterval(), source.getAmountMinor(), version,
                    source.getProviderPriceRef(), true, source.getValidFrom()));
            audit.success(ACTIVATED, adminId, LogMasking.maskEmail(email), TARGET, saved.getId(), detail(saved) + " from=" + source.getId());
            return response(saved);
        });
    }

    /** Migration §6: at least one active price, in this market, for every paid model enabled now. */
    @Override
    public boolean availableIn(Market market) {
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        if (config == null) return false;
        Instant now = clock.now();
        return config.models().stream().map(Enum::name).filter(code -> plans.findById(code).map(SubscriptionPlan::isPaid).orElse(false))
                .allMatch(code -> prices.hasActivePrice(code, market.name(), now));
    }

    /** Versions of one plan are numbered one at a time; the unique key is the backstop. */
    private int nextVersion(String planCode, String market, String interval) {
        jdbc.queryForList("select code from subscription_plan where code = ? for update", String.class, planCode);
        return prices.maxVersion(planCode, market, interval) + 1;
    }

    private static Money amount(BigDecimal value, Market market) {
        Money money;
        try {
            money = Money.ofMinor(value, market.currency());
        } catch (IllegalArgumentException e) {
            throw invalid("amountMinor", "Enter a whole number of minor units");
        }
        if (money.minor() <= 0) throw invalid("amountMinor", "Enter an amount above zero");
        return money;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw invalid(field, "Choose a valid value");
        }
    }

    private static FieldValidationException invalid(String field, String message) {
        return new FieldValidationException(Map.of(field, message));
    }

    private static String detail(PlanPrice p) {
        return p.getPlanCode() + " " + p.getMarket() + " " + p.getInterval() + " v" + p.getVersion() + " " + p.getAmountMinor() + " " + p.getCurrency() + " active=" + p.isActive();
    }

    private static PlanPriceResponse response(PlanPrice p) {
        return new PlanPriceResponse(p.getId(), p.getPlanCode(), p.getMarket(), p.getCurrency(), p.getInterval(), p.getAmountMinor(), p.getVersion(), p.getProviderPriceRef(), p.isActive(), p.getValidFrom());
    }
}
