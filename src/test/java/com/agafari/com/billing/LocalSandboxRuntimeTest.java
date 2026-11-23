package com.agafari.com.billing;

import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF B-L1 (register §93, §94): an active local sandbox against the real checkout, inbox and payment-outcome services.
 * <ul>
 *   <li>US only.</li>
 *   <li>New checkouts stop at the cutoff ({@code until} − 45 min), and a checkout never outlives {@code until}, so nothing
 *   started in the window can be paid after it.</li>
 *   <li>Webhooks are always recorded durably. During the drain ({@code until} + 24 h), a payment made inside the window is
 *   still verified and applied (delayed webhook); a payment made after it is quarantined, never applied; duplicates have one
 *   effect.</li>
 *   <li>After the drain, events stay recorded and unapplied, for reconciliation.</li>
 * </ul>
 * The sandbox is built from valid synthetic inputs through the production constructor; nothing in production code can
 * switch it on from a test.
 */
@Import(LocalSandboxRuntimeTest.ActiveSandbox.class)
class LocalSandboxRuntimeTest extends CheckoutTestBase {

    static final Instant UNTIL = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
    private static final String US_PROVIDER = "FAKE_US";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @TestConfiguration
    static class ActiveSandbox {
        @Bean
        @Primary
        LocalSandboxActivation activeLocalSandbox(MonetizationClock clock) {
            return new LocalSandboxActivation(new LocalSandboxActivation.Inputs(true, true, UNTIL.toString(), true, List.of("local-sandbox"), true,
                    LocalSandboxActivation.KeyKind.TEST, "acct_" + "SyntheticBL1",
                    Map.of("app.billing.stripe.success-url", "http://127.0.0.1:5212/billing/return", "app.billing.stripe.cancel-url", "http://127.0.0.1:5212/billing/cancelled",
                            "app.billing.stripe.trusted-origins", "http://127.0.0.1:5212", "app.public-menu-base-url", "http://127.0.0.1:5212"),
                    "jdbc:postgresql://127.0.0.1:54409/agafari_sandbox_runtime", Set.of(), false), clock);
        }
    }

    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private InboxService inbox;
    @Autowired private LocalSandboxActivation sandbox;

    @AfterEach
    void reset() {
        outcomes.setBillingEnabled(Market.US, false);
        outcomes.setBillingEnabled(Market.ET, false);
    }

    private void at(Instant when) {
        clock.setOffset(Duration.between(Instant.now(), when));
    }

    private Tenant usOwner(String label) {
        Tenant t = data.tenant(label + "-" + SEQ.incrementAndGet());
        jdbc.update("update business set country = 'US' where id = ?", t.businessId());
        return t;
    }

    /** An OPEN US checkout written directly (the creation path is tested separately), expiring at {@code expiresAt}. */
    private String usCheckout(Tenant t, String priceId, Instant expiresAt) {
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, 'US', ?) on conflict (business_id, provider) do nothing",
                UUID.randomUUID().toString(), t.businessId(), US_PROVIDER);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, provider, provider_session_ref, provider_session_url, "
                        + "status, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at) "
                        + "values (?, ?, ?, ?, 1, 'NEW', ?, ?, 'https://fake.test/pay', 'OPEN', ?, 'hash', ?, 2500, 'USD', ?)",
                id, t.businessId(), t.owner().getId(), priceId, US_PROVIDER, "sess_" + id, UUID.randomUUID().toString(), Timestamp.from(expiresAt),
                Timestamp.from(expiresAt.minus(Duration.ofHours(2))));
        return id;
    }

    private String usPaid(Tenant t, String checkoutId, Instant tPay) {
        String ref = "pay_bl1_" + SEQ.incrementAndGet();
        fakeUs.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(2500, Currency.USD), tPay,
                Map.of(PaymentMetadata.CHECKOUT_ID, checkoutId, PaymentMetadata.BUSINESS_ID, t.businessId(), PaymentMetadata.PROVIDER_SESSION_REF, "sess_" + checkoutId)));
        return ref;
    }

    /** Stores a webhook exactly as the controller does; returns whether it was new. */
    private boolean deliver(String eventId, String paymentRef) {
        return inbox.store(US_PROVIDER, new InboundEvent(eventId, "payment.succeeded"),
                ("{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\",\"payment_ref\":\"" + paymentRef + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private String eventRow(String eventId) {
        return jdbc.queryForObject("select id from provider_event where provider = ? and event_id = ?", String.class, US_PROVIDER, eventId);
    }

    private Map<String, Object> eventState(String eventId) {
        return jdbc.queryForMap("select status, last_error from provider_event where provider = ? and event_id = ?", US_PROVIDER, eventId);
    }

    private int subscriptions(Tenant t) {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ? and source = 'PROVIDER'", Integer.class, t.businessId());
    }

    private int payments(Tenant t) {
        return jdbc.queryForObject("select count(*) from payment where business_id = ?", Integer.class, t.businessId());
    }

    // ---- the window ----

    @Test
    void theSandboxInUseHereIsActiveAndTheGatesStayOpen() {
        assertThat(sandbox.active()).isTrue();
        assertThat(ProviderActivationReadiness.ready()).isFalse();
    }

    @Test
    void ethiopianBillingIsRefusedWhileTheSandboxIsActive() throws Exception {
        assertThat(httpStatus(owner, "bl1-et-1", price)).as("ET checkout: as with billing off").isEqualTo(404);
        assertThat(checkouts.checkoutAvailable(owner.businessId())).isFalse();
        outcomes.setBillingEnabled(Market.ET, true);
        assertThat(outcomes.billingActive(Market.ET)).as("no ET payment outcome").isFalse();
    }

    @Test
    void newCheckoutsStopAtTheCutoffAndACheckoutNeverOutlivesTheWindow() throws Exception {
        Tenant us = usOwner("bl1-cut");
        String usPrice = price("MODEL_4", "US", "USD", 2500);
        at(UNTIL.minus(Duration.ofHours(2)));
        assertThat(checkouts.checkoutAvailable(us.businessId())).isTrue();
        assertThat(httpStatus(us, "bl1-cut-1", usPrice)).isNotEqualTo(404);
        Timestamp expires = jdbc.queryForObject("select expires_at from checkout_session where business_id = ?", Timestamp.class, us.businessId());
        assertThat(expires.toInstant()).as("capped at the end of the window (not now + 24 h)").isEqualTo(UNTIL);

        Tenant late = usOwner("bl1-late");
        at(UNTIL.minus(LocalSandboxActivation.CHECKOUT_CUTOFF));
        assertThat(checkouts.checkoutAvailable(late.businessId())).isFalse();
        assertThat(httpStatus(late, "bl1-cut-2", usPrice)).as("from the cutoff: as with billing off").isEqualTo(404);
        at(UNTIL.plus(Duration.ofHours(1)));
        assertThat(httpStatus(late, "bl1-cut-3", usPrice)).as("after the end").isEqualTo(404);
    }

    @Test
    void anOpenCheckoutCanStillBeCancelledDuringTheDrain() throws Exception {
        Tenant us = usOwner("bl1-cancel");
        String c = usCheckout(us, price("MODEL_4", "US", "USD", 2500), UNTIL);
        at(UNTIL.plus(Duration.ofHours(1)));
        int status = mockMvc.perform(post("/api/billing/checkouts/" + c + "/cancel").header("Authorization", data.bearer(us))).andReturn().getResponse().getStatus();
        assertThat(status).as("cancelling reduces risk; allowed while draining").isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from checkout_session where id = ?", String.class, c)).isEqualTo("CANCEL_REQUESTED");
    }

    // ---- delayed and duplicate webhooks around the end ----

    @Test
    void aPaymentMadeInsideTheWindowWhoseWebhookArrivesAfterItIsStillApplied() {
        outcomes.setBillingEnabled(Market.US, true);
        Tenant us = usOwner("bl1-delayed");
        String c = usCheckout(us, price("MODEL_4", "US", "USD", 2500), UNTIL);
        String ref = usPaid(us, c, UNTIL.minus(Duration.ofMinutes(10)));
        at(UNTIL.plus(Duration.ofHours(3))); // the webhook is late
        assertThat(deliver("evt_bl1_delayed", ref)).isTrue();
        assertThat(outcomes.process(eventRow("evt_bl1_delayed"))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(subscriptions(us)).as("backend-verified activation from evidence of a payment inside the window").isEqualTo(1);
        assertThat(payments(us)).isEqualTo(1);
    }

    @Test
    void duplicateWebhooksAroundTheEndAreRecordedOnceAndHaveOneEffect() {
        outcomes.setBillingEnabled(Market.US, true);
        Tenant us = usOwner("bl1-dup");
        String c = usCheckout(us, price("MODEL_4", "US", "USD", 2500), UNTIL);
        String ref = usPaid(us, c, UNTIL.minus(Duration.ofMinutes(5)));
        at(UNTIL.minus(Duration.ofMinutes(1)));
        assertThat(deliver("evt_bl1_dup", ref)).isTrue();
        at(UNTIL.plus(Duration.ofMinutes(1)));
        assertThat(deliver("evt_bl1_dup", ref)).as("the same event again: stored once").isFalse();
        assertThat(outcomes.process(eventRow("evt_bl1_dup"))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(outcomes.process(eventRow("evt_bl1_dup"))).isEqualTo(PaymentOutcomeService.Result.ALREADY_DONE);
        assertThat(deliver("evt_bl1_dup_other", ref)).as("another event naming the same payment").isTrue();
        outcomes.process(eventRow("evt_bl1_dup_other"));
        assertThat(payments(us)).as("one charge").isEqualTo(1);
        assertThat(subscriptions(us)).as("one subscription").isEqualTo(1);
    }

    @Test
    void aPaymentMadeAfterTheWindowIsQuarantinedAndNeverApplied() {
        outcomes.setBillingEnabled(Market.US, true);
        Tenant us = usOwner("bl1-after");
        String c = usCheckout(us, price("MODEL_4", "US", "USD", 2500), UNTIL.plus(Duration.ofHours(5))); // a session that (wrongly) outlived it
        String ref = usPaid(us, c, UNTIL.plus(Duration.ofMinutes(1)));
        at(UNTIL.plus(Duration.ofMinutes(30)));
        deliver("evt_bl1_after", ref);
        assertThat(outcomes.process(eventRow("evt_bl1_after"))).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        Map<String, Object> e = eventState("evt_bl1_after");
        assertThat(e.get("status")).as("durably recorded").isEqualTo("QUARANTINED");
        assertThat(String.valueOf(e.get("last_error"))).contains("LOCAL_SANDBOX");
        assertThat(subscriptions(us)).isZero();
        assertThat(payments(us)).as("no ledger charge from a payment outside the window").isZero();
    }

    @Test
    void afterTheDrainWebhooksAreStillRecordedButNothingIsApplied() {
        outcomes.setBillingEnabled(Market.US, true);
        Tenant us = usOwner("bl1-ended");
        String c = usCheckout(us, price("MODEL_4", "US", "USD", 2500), UNTIL);
        String ref = usPaid(us, c, UNTIL.minus(Duration.ofMinutes(10)));
        at(UNTIL.plus(LocalSandboxActivation.DRAIN));
        assertThat(deliver("evt_bl1_ended", ref)).as("recorded durably").isTrue();
        assertThatThrownBy(() -> outcomes.process(eventRow("evt_bl1_ended"))).isInstanceOf(PermanentJobException.class);
        Map<String, Object> e = eventState("evt_bl1_ended");
        assertThat(e.get("status")).as("left for reconciliation, not lost").isEqualTo("PENDING");
        assertThat(String.valueOf(e.get("last_error"))).contains("not active");
        assertThat(subscriptions(us)).isZero();
        assertThat(outcomes.billingActive(Market.US)).isFalse();
    }
}
