package com.agafari.com.billing.stripe;

import com.agafari.com.billing.Currency;
import com.agafari.com.billing.PriceInterval;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderInvoiceState;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderSubscriptionSnapshot;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.StateTrigger;
import com.agafari.com.billing.port.VerifiedPayment;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import static com.agafari.com.billing.stripe.RenewalFixtures.epoch;
import static com.agafari.com.billing.stripe.RenewalFixtures.json;
import static com.agafari.com.billing.stripe.RenewalFixtures.num;
import static com.agafari.com.billing.stripe.RenewalFixtures.raw;
import static com.agafari.com.billing.stripe.RenewalFixtures.str;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R5-03 (addendum §12 A2/A4): the real Stripe adapter and SDK against the {@link RenewalFixtures AUTHENTIC} RF capture,
 * served byte for byte through {@link StripeStub} (no network). Turns the addendum's [A] assumptions about renewal invoices
 * into verified facts, and checks the adapter classifies and re-fetches them as designed.
 */
class AuthenticRenewalContractTest {

    private static final String IN = str("rf_invoice_recovered", "id");
    private static final String SUB = str("rf_subscription_recovered", "id");
    private static final String CUS = str("rf_subscription_recovered", "customer");
    private static final String PRICE = str("rf_subscription_recovered", "items.data[0].price.id");
    private static final String PI = str("rf_invoice_recovered", "payments.data[0].payment.payment_intent");

    private StripeStub stub;
    private StripeProviderClient client;

    @BeforeEach
    void setUp() {
        stub = new StripeStub();
        StripeCredentials credentials = StripeCredentials.fromEnvironment(Map.of(StripeCredentials.ENV_SECRET_KEY, "sk_" + "test_" + "SyntheticR503Contract00001",
                StripeCredentials.ENV_WEBHOOK_SECRET, "whsec_" + "SyntheticR503Contract00002")::get);
        client = new StripeProviderClient(credentials, new StripeSettings(null, null, null), stub, Clock.systemUTC());
    }

    private void serveRecovered(String invoiceJson) {
        stub.on("GET", "/v1/invoices/" + IN, c -> StripeStub.ok(invoiceJson));
        stub.on("GET", "/v1/payment_intents/" + PI, c -> StripeStub.ok(RenewalFixtures.recoveredPaymentIntent()));
        stub.on("GET", "/v1/subscriptions/" + SUB, c -> StripeStub.ok(raw("rf_subscription_recovered")));
    }

    // ---- [A] → [V]: what a Stripe renewal invoice is ----

    @Test
    void aRenewalInvoiceIsASubscriptionCycleInvoiceWhoseLinePeriodStartsAtThePreviousPeriodEnd() {
        assertThat(str("rf_invoice_recovered", "billing_reason")).isEqualTo("subscription_cycle");
        assertThat(str("rf_invoice_recovered", "parent.subscription_details.subscription")).isEqualTo(SUB);
        assertThat(json("rf_invoice_recovered").getAsJsonObject("lines").getAsJsonArray("data")).hasSize(1);
        // §2: the line period is the new service period, contiguous with the period the first invoice paid (PD-3)
        assertThat(num("rf_invoice_recovered", "lines.data[0].period.start")).isEqualTo(num("rf_subscription_create", "items.data[0].current_period_end"));
        // and the invoice's own period_* fields name the PREVIOUS period, so they are never used
        assertThat(num("rf_invoice_recovered", "period_end")).isEqualTo(num("rf_invoice_recovered", "lines.data[0].period.start"));
        assertThat(num("rf_invoice_recovered", "period_start")).isLessThan(num("rf_invoice_recovered", "period_end"));
    }

    @Test
    void theSubscriptionAndInvoiceCarryNoAgafariCheckoutIdentity() {
        assertThat(json("rf_invoice_recovered").getAsJsonObject("parent").getAsJsonObject("subscription_details").getAsJsonObject("metadata").size()).isZero();
        assertThat(json("rf_subscription_recovered").getAsJsonObject("metadata").size()).isZero();
    }

    @Test
    void theRecoveryReusesTheFailedPaymentIntentAndLeavesTheAttemptCountUnchanged() {
        // O-RF-1: one invoice payment record, on the PaymentIntent that first failed; identity is therefore the invoice
        assertThat(PI).isEqualTo(str("rf_payment_intent_failed", "id"));
        assertThat(str("rf_payment_intent_failed", "status")).isEqualTo("requires_payment_method");
        JsonArray payments = json("rf_invoice_recovered").getAsJsonObject("payments").getAsJsonArray("data");
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getAsJsonObject().get("status").getAsString()).isEqualTo("paid");
        // O-RF-2: attempt_count is no renewal identity
        assertThat(num("rf_invoice_failed", "attempt_count")).isEqualTo(num("rf_invoice_recovered", "attempt_count"));
    }

    @Test
    void theFailureLeavesTheInvoiceOpenAndTheSubscriptionPastDueWithThatInvoiceLatest() {
        assertThat(str("rf_invoice_failed", "status")).isEqualTo("open");
        assertThat(json("rf_invoice_failed").get("attempted").getAsBoolean()).isTrue();
        assertThat(str("rf_subscription_past_due", "status")).isEqualTo("past_due");
        assertThat(str("rf_subscription_past_due", "latest_invoice")).isEqualTo(IN);
        assertThat(str("rf_subscription_recovered", "status")).isEqualTo("active");
    }

    @Test
    void theEventsArePinnedTestModeAndNameWhatToReFetch() {
        for (String e : new String[]{"rf_event_invoice_paid", "rf_event_payment_failed", "rf_event_subscription_updated"}) {
            assertThat(str(e, "api_version")).as(e).isEqualTo("2026-10-28.endive");
            assertThat(json(e).get("livemode").getAsBoolean()).as(e).isFalse();
        }
        assertThat(str("rf_event_invoice_paid", "type")).isEqualTo("invoice.paid");
        assertThat(str("rf_event_payment_failed", "type")).isEqualTo("invoice.payment_failed");
        assertThat(str("rf_event_subscription_updated", "type")).isEqualTo("customer.subscription.updated");
        assertThat(str("rf_event_subscription_updated", "data.object.status")).isEqualTo("past_due");
    }

    // ---- the adapter on the authentic objects ----

    @Test
    void theRecoveredInvoiceVerifiesAsQualifyingRenewalEvidenceWithTheLinePeriod() {
        serveRecovered(raw("rf_invoice_recovered"));
        VerifiedPayment v = client.verifyPayment(IN);
        assertThat(v.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(v.amount().minor()).isEqualTo(2500);
        assertThat(v.amount().currency()).isEqualTo(Currency.USD);
        assertThat(v.paidAt()).isEqualTo(epoch("rf_invoice_recovered", "status_transitions.paid_at"));
        assertThat(v.metadata()).as("§37: never the original checkout identity").isEmpty();
        ProviderBilling b = v.billing();
        assertThat(b.renewal()).isTrue();
        assertThat(b.qualifying()).as(String.valueOf(b.evidenceProblem())).isTrue();
        assertThat(b.invoiceRef()).isEqualTo(IN);
        assertThat(b.subscriptionRef()).isEqualTo(SUB);
        assertThat(b.customerRef()).isEqualTo(CUS);
        assertThat(b.priceRef()).isEqualTo(PRICE);
        assertThat(b.interval()).isEqualTo(PriceInterval.MONTH);
        assertThat(b.periodStart()).isEqualTo(epoch("rf_invoice_recovered", "lines.data[0].period.start"));
        assertThat(b.periodEnd()).isEqualTo(epoch("rf_invoice_recovered", "lines.data[0].period.end"));
        assertThat(b.moneyMovementRef()).isEqualTo(PI);
        assertThat(stub.calls("GET", "/v1/checkout/sessions")).as("a renewal never looks for a checkout").isEmpty();
    }

    @Test
    void theFailedInvoiceVerifiesAsPendingNeverAsAPayment() {
        stub.on("GET", "/v1/invoices/" + IN, c -> StripeStub.ok(raw("rf_invoice_failed")));
        assertThat(client.verifyPayment(IN).status()).isEqualTo(ProviderPaymentStatus.PENDING);
    }

    @Test
    void aTaxedRenewalInvoiceIsReportedNotSmoothedOverAndKeepsItsOwner() {
        JsonObject taxed = json("rf_invoice_recovered");
        JsonArray taxes = new JsonArray();
        JsonObject tax = new JsonObject();
        tax.addProperty("amount", 100);
        taxes.add(tax);
        taxed.add("total_taxes", taxes); // SYNTHETIC mutation of the authentic invoice
        serveRecovered(taxed.toString());
        ProviderBilling b = client.verifyPayment(IN).billing();
        assertThat(b.renewal()).isTrue();
        assertThat(b.qualifying()).isFalse();
        assertThat(b.subscriptionRef()).as("the restaurant still comes from the subscription").isEqualTo(SUB);
        assertThat(b.customerRef()).isEqualTo(CUS);
    }

    @Test
    void aRenewalIsJudgedWithoutTheSubscriptionsStatusOrCurrentPeriod() {
        // §2: Stripe's subscription current_period_* and status are not evidence; a cancelled subscription's paid invoice still verifies
        JsonObject cancelled = json("rf_subscription_recovered");
        cancelled.addProperty("status", "canceled"); // SYNTHETIC mutation
        cancelled.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().addProperty("current_period_start", 1900000000L);
        stub.on("GET", "/v1/invoices/" + IN, c -> StripeStub.ok(raw("rf_invoice_recovered")));
        stub.on("GET", "/v1/payment_intents/" + PI, c -> StripeStub.ok(RenewalFixtures.recoveredPaymentIntent()));
        stub.on("GET", "/v1/subscriptions/" + SUB, c -> StripeStub.ok(cancelled.toString()));
        assertThat(client.verifyPayment(IN).billing().qualifying()).isTrue();
    }

    @Test
    void anotherCustomersSubscriptionMakesTheEvidenceNonQualifying() {
        JsonObject other = json("rf_subscription_recovered");
        other.addProperty("customer", "cus_SyntheticOther"); // SYNTHETIC mutation
        stub.on("GET", "/v1/invoices/" + IN, c -> StripeStub.ok(raw("rf_invoice_recovered")));
        stub.on("GET", "/v1/payment_intents/" + PI, c -> StripeStub.ok(RenewalFixtures.recoveredPaymentIntent()));
        stub.on("GET", "/v1/subscriptions/" + SUB, c -> StripeStub.ok(other.toString()));
        assertThat(client.verifyPayment(IN).billing().qualifying()).isFalse();
    }

    @Test
    void theInvoiceStateReFetchReadsTheAuthenticFailedInvoice() {
        stub.on("GET", "/v1/invoices/" + IN, c -> StripeStub.ok(raw("rf_invoice_failed")));
        ProviderInvoiceState s = client.invoiceState(IN);
        assertThat(s.status()).isEqualTo(ProviderInvoiceState.Status.OPEN);
        assertThat(s.renewal()).isTrue();
        assertThat(s.subscriptionRef()).isEqualTo(SUB);
        assertThat(s.customerRef()).isEqualTo(CUS);
        assertThat(s.attempted()).isTrue();
        assertThat(s.periodStart()).isEqualTo(epoch("rf_invoice_failed", "lines.data[0].period.start"));
        assertThat(s.livemode()).isFalse();
    }

    @Test
    void theSubscriptionStateReFetchReadsTheAuthenticPastDueSubscription() {
        stub.on("GET", "/v1/subscriptions/" + SUB, c -> StripeStub.ok(raw("rf_subscription_past_due")));
        ProviderSubscriptionSnapshot s = client.subscriptionState(SUB);
        assertThat(s.status()).isEqualTo(ProviderSubscriptionState.Status.PAST_DUE);
        assertThat(s.latestInvoiceRef()).isEqualTo(IN);
        assertThat(s.customerRef()).isEqualTo(CUS);
        assertThat(s.endedAt()).isNull();
        assertThat(s.livemode()).isFalse();
    }

    @Test
    void theAuthenticEventBodiesNameOnlyWhatToReFetch() {
        StripeSignatureVerifier verifier = new StripeSignatureVerifier("whsec_" + "SyntheticR503Contract00002", Clock.systemUTC());
        assertThat(verifier.paymentReference(bytes("rf_event_invoice_paid"))).contains(IN);
        assertThat(verifier.stateTrigger(bytes("rf_event_invoice_paid"))).isEmpty();
        assertThat(verifier.paymentReference(bytes("rf_event_payment_failed"))).isEmpty();
        assertThat(verifier.stateTrigger(bytes("rf_event_payment_failed"))).isEqualTo(Optional.of(new StateTrigger(StateTrigger.Kind.INVOICE, IN)));
        assertThat(verifier.stateTrigger(bytes("rf_event_subscription_updated"))).isEqualTo(Optional.of(new StateTrigger(StateTrigger.Kind.SUBSCRIPTION, SUB)));
        String deleted = raw("rf_event_subscription_updated").replace("customer.subscription.updated", "customer.subscription.deleted"); // SYNTHETIC: RX not captured yet
        assertThat(verifier.stateTrigger(deleted.getBytes(StandardCharsets.UTF_8))).isEqualTo(Optional.of(new StateTrigger(StateTrigger.Kind.SUBSCRIPTION, SUB)));
    }

    private static byte[] bytes(String name) {
        return raw(name).getBytes(StandardCharsets.UTF_8);
    }
}
