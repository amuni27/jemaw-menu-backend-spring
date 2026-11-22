package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R5-03-E (D-R5, N5 in register §69): the owner opens the provider's hosted card-update page (Stripe Customer Portal)
 * for their own stored customer, only while a PROVIDER plan is ACTIVE or PAST_DUE and the market's billing is active. Agafari
 * never sees card data; the URL is never logged or audited. PostgreSQL; the fake ET provider (SYNTHETIC URLs).
 */
class BillingPortalTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PATH = "/api/billing/subscription/portal-session";

    @Autowired private ObjectMapper mapper;

    private String priceRef;

    @BeforeEach
    void providerPrice() {
        priceRef = "price_portal_" + SEQ.incrementAndGet();
        price = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', ?, 94, ?, true, now() - interval '400 days')", price, ET_AMOUNT, priceRef);
    }

    /** A PROVIDER row with a stored provider customer (R5-02 shape); returns the customer. */
    private String provider(Tenant t) {
        String n = SEQ.incrementAndGet() + "_" + t.businessId().substring(0, 6);
        Instant start = micros(clock.now().minus(Duration.ofDays(1))), end = oneMonth(start);
        String c = checkout(t, CheckoutKind.NEW, null), in = "in_portal_" + n;
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(clock.now()), metadata(c),
                new ProviderBilling(in, "sub_portal_" + n, "cus_portal_" + n, priceRef, PriceInterval.MONTH, start, end, "pi_portal_" + n, null)));
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return "cus_portal_" + n;
    }

    private MockHttpServletResponse open(Tenant t) throws Exception {
        return mockMvc.perform(post(PATH).header("Authorization", data.bearer(t))).andReturn().getResponse();
    }

    private String code(MockHttpServletResponse r) throws Exception {
        return mapper.readTree(r.getContentAsString()).path("code").asString();
    }

    @Test
    void anActivePlanOpensThePortalForTheRestaurantsOwnStoredCustomer() throws Exception {
        String customer = provider(owner);
        MockHttpServletResponse r = open(owner);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(r.getContentAsString()).path("url").asString()).isEqualTo("https://fake.test/portal/" + customer);
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat(fakeEt.portalCustomers()).containsExactly(customer);
        String detail = jdbc.queryForObject("select detail from admin_audit_event where action = 'BILLING_PORTAL_SESSION_CREATED' and target_id = ?", String.class, owner.businessId());
        assertThat(detail).as("the URL is a bearer link: never audited").doesNotContain("https://").doesNotContain("fake.test");
    }

    @Test
    void aPastDuePlanOpensThePortal() throws Exception {
        provider(owner);
        String sub = current(owner);
        Instant pe = jdbc.queryForObject("select period_end from business_subscription where id = ?", java.sql.Timestamp.class, sub).toInstant();
        clock.setOffset(Duration.between(Instant.now(), pe.plus(Duration.ofHours(2))));
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            lifecycle.markPastDue(sub, pe, clock.now());
        });
        assertThat(open(owner).getStatus()).isEqualTo(200);
        clock.reset();
    }

    @Test
    void withoutAProviderPlanThePortalIsRefusedAndTheProviderIsNeverAsked() throws Exception {
        MockHttpServletResponse none = open(owner);
        startTrial(owner, clock.now().plus(Duration.ofDays(5)));
        MockHttpServletResponse trial = open(owner);
        Tenant free = data.tenant("portal-free");
        startOwnerFree(free);
        MockHttpServletResponse own = open(free);
        for (MockHttpServletResponse r : new MockHttpServletResponse[]{none, trial, own}) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("PORTAL_NOT_ELIGIBLE");
        }
        assertThat(fakeEt.portalCustomers()).isEmpty();
    }

    @Test
    void aProviderPlanWithoutAStoredCustomerIsRefused() throws Exception {
        assertThat(payAndProcess(checkout(owner, CheckoutKind.NEW, null), micros(clock.now()))).isEqualTo(PaymentOutcomeService.Result.PROCESSED); // a one-off provider: no customer
        MockHttpServletResponse r = open(owner);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("PORTAL_NOT_ELIGIBLE");
        assertThat(fakeEt.portalCustomers()).isEmpty();
    }

    @Test
    void anEndedPlanCanNoLongerOpenThePortal() throws Exception {
        provider(owner);
        String sub = current(owner);
        new TransactionTemplate(transactions).executeWithoutResult(x -> jdbc.update(
                "update business_subscription set status = 'EXPIRED', ended_at = now(), ended_reason = 'ADMIN' where id = ?", sub));
        assertThat(open(owner).getStatus()).isEqualTo(409);
        assertThat(fakeEt.portalCustomers()).isEmpty();
    }

    @Test
    void withBillingInactiveTheRouteIsNotFound() throws Exception {
        provider(owner);
        outcomes.setBillingEnabled(Market.ET, false);
        assertThat(open(owner).getStatus()).isEqualTo(404);
        assertThat(fakeEt.portalCustomers()).isEmpty();
    }

    @Test
    void eachOwnerOnlyEverGetsTheirOwnCustomer() throws Exception {
        String mine = provider(owner);
        Tenant other = data.tenant("portal-other");
        String theirs = provider(other);
        open(owner);
        open(other);
        assertThat(fakeEt.portalCustomers()).containsExactly(mine, theirs);
    }

    @Test
    void aProviderTimeoutOrRefusalIsReportedWithoutDetail() throws Exception {
        provider(owner);
        fakeEt.portalFailure(new ProviderTimeoutException("synthetic timeout"));
        MockHttpServletResponse timeout = open(owner);
        assertThat(timeout.getStatus()).isEqualTo(503);
        assertThat(code(timeout)).isEqualTo("PORTAL_UNAVAILABLE");
        fakeEt.portalFailure(new ProviderRejectedException("synthetic refusal with internal detail"));
        MockHttpServletResponse refused = open(owner);
        assertThat(refused.getStatus()).isEqualTo(502);
        assertThat(code(refused)).isEqualTo("PORTAL_UNAVAILABLE");
        assertThat(refused.getContentAsString()).doesNotContain("internal detail");
    }

    @Test
    void theRouteNeedsAnOwner() throws Exception {
        assertThat(mockMvc.perform(post(PATH)).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
