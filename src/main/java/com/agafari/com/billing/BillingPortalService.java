package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.ProviderPortalSession;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.exception.BillingPortalRefusedException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.subscription.HeldFunding;
import com.agafari.com.subscription.LifecycleStatus;
import com.agafari.com.subscription.SubscriptionSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * AGF SUB-R5-03-E (D-R5, N5 in register §69): opens the provider's hosted card-update page (Stripe Customer Portal) for the
 * owner. Eligible: the restaurant's current PROVIDER subscription is ACTIVE or PAST_DUE, its provider customer is stored
 * (U5: only that customer, never another), and the market's billing is active (otherwise the route doesn't exist). The
 * provider is called outside any transaction. Agafari stores no card data; the URL is never logged or audited.
 */
@Slf4j
@Service
public class BillingPortalService {

    static final String CREATED = "BILLING_PORTAL_SESSION_CREATED";

    private final BusinessSubscriptionRepository subscriptions;
    private final BillingStrategyRegistry registry;
    private final HeldFunding billingGate; // the market billing gate, through its narrow interface
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public BillingPortalService(BusinessSubscriptionRepository subscriptions, BillingStrategyRegistry registry, HeldFunding billingGate, JdbcTemplate jdbc,
                                AdminAuditService audit, PlatformTransactionManager transactions) {
        this.subscriptions = subscriptions;
        this.registry = registry;
        this.billingGate = billingGate;
        this.jdbc = jdbc;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The portal URL for the caller's own restaurant. */
    public String open(String businessId) {
        ProviderCallGuard.assertNoTransaction();
        if (!billingGate.billingActive(businessId)) throw new NotFoundException("Not found");
        BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        LifecycleStatus status = current == null ? null : LifecycleStatus.of(current.getStatus());
        if (current == null || SubscriptionSource.of(current.getSource()) != SubscriptionSource.PROVIDER || current.getProvider() == null
                || (status != LifecycleStatus.ACTIVE && status != LifecycleStatus.PAST_DUE)) {
            throw notEligible("There is no paid plan whose card can be updated");
        }
        List<String> customer = jdbc.queryForList("select provider_customer_ref from billing_account where business_id = ? and provider = ?", String.class, businessId,
                current.getProvider());
        if (customer.size() != 1 || customer.get(0) == null || customer.get(0).isBlank()) throw notEligible("There is no stored payment account for this plan");
        BillingStrategy strategy = registry.forProvider(current.getProvider()).orElseThrow(() -> notEligible("The plan's payment provider isn't available"));
        ProviderPortalSession session;
        try {
            session = strategy.client().createPortalSession(customer.get(0));
        } catch (UnsupportedOperationException e) {
            throw notEligible("The plan's payment provider has no card-update page");
        } catch (ProviderTimeoutException e) {
            log.warn("event=billing.portal_unavailable businessId={} cause=timeout", businessId);
            throw new BillingPortalRefusedException(HttpStatus.SERVICE_UNAVAILABLE, BillingPortalRefusedException.UNAVAILABLE, "The card-update page is unavailable; try again");
        } catch (ProviderRejectedException e) {
            log.warn("event=billing.portal_unavailable businessId={} cause=refused", businessId);
            throw new BillingPortalRefusedException(HttpStatus.BAD_GATEWAY, BillingPortalRefusedException.UNAVAILABLE, "The card-update page is unavailable");
        }
        String subscriptionId = current.getId();
        transaction.executeWithoutResult(s -> audit.success(CREATED, null, AdminAuditService.SYSTEM, "BUSINESS", businessId, "subscription=" + subscriptionId + " status=" + status));
        return session.url();
    }

    private static BillingPortalRefusedException notEligible(String message) {
        return new BillingPortalRefusedException(HttpStatus.CONFLICT, BillingPortalRefusedException.NOT_ELIGIBLE, message);
    }
}
