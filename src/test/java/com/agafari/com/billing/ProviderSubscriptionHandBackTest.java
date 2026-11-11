package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R5-02 (PO U2/U4): when a provider subscription can't be cancelled safely, the payment's <b>existing</b> outcome
 * exception goes to an operator. {@code AUTO_HANDLING → OPEN} once, an exception already with an operator gets an audit
 * note; code and facts never change; never a second exception. (Stripe can't reach AUTO_HANDLING in R5-02, since its
 * automatic-refund capability is off, so this uses the fake provider's row-4 automatic refund.)
 */
class ProviderSubscriptionHandBackTest extends PaymentOutcomeTestBase {

    @Autowired private ExceptionService exceptionService;

    private String autoRefunded(Tenant t) {
        String c = checkout(t, CheckoutKind.NEW, null);
        checkouts.cancel(t.businessId(), c);
        process(event(paid(c, Instant.now().plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS))));
        return jdbc.queryForObject("select id from payment where business_id = ?", String.class, t.businessId());
    }

    private String handBack(Tenant t, String payment, String reason) {
        return new TransactionTemplate(transactions).execute(s -> {
            locks.restaurant(t.businessId());
            return exceptionService.handBackPayment(payment, reason, "detail");
        });
    }

    @Test
    void anAutomaticallyHandledExceptionIsHandedBackOnceWithCodeAndFactsUnchanged() {
        String payment = autoRefunded(owner);
        Map<String, Object> before = onlyException(owner);
        assertThat(before).containsEntry("status", "AUTO_HANDLING");

        String id = handBack(owner, payment, "C5_OTHER_OPEN_INVOICES");
        Map<String, Object> after = onlyException(owner);

        assertThat(id).isEqualTo(before.get("id"));
        assertThat(after).containsEntry("status", "OPEN").containsEntry("code", before.get("code")).containsEntry("facts", before.get("facts"))
                .containsEntry("refund_request_id", before.get("refund_request_id"));
        assertThat(count("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_HANDED_BACK' and target_id = ? and detail like '%C5_OTHER_OPEN_INVOICES%'", id)).isEqualTo(1);

        handBack(owner, payment, "CANCELLATION_OUTCOME_UNCERTAIN"); // already with an operator: an audit note only
        assertThat(onlyException(owner)).containsEntry("status", "OPEN").containsEntry("row_version", after.get("row_version"));
        assertThat(count("select count(*) from admin_audit_event where action = 'PROVIDER_SUBSCRIPTION_HANDED_BACK' and target_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from billing_exception where payment_id = ?", payment)).as("never a second exception").isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void aPaymentWithoutAnExceptionIsLeftAlone() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        process(event(paid(c, Instant.now().plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS))));
        String payment = jdbc.queryForObject("select id from payment where business_id = ?", String.class, owner.businessId());
        assertThat(handBack(owner, payment, "C1_NOT_A_TERMINAL_VERIFIED_CHARGE")).isNull();
        assertThat(exceptions(owner)).isZero();
    }
}
