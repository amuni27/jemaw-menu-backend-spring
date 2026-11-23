package com.agafari.com.billing.stripe;

import com.agafari.com.billing.port.ProviderPortalSession;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R5-03-E: the real Stripe adapter and SDK creating a Customer Portal session through {@link StripeStub} (no network;
 * <b>SYNTHETIC</b> responses). Only the stored customer, the configured card-update-only configuration and a trusted return
 * URL; a live-mode session is refused; nothing is attempted while the portal settings are incomplete.
 */
class StripePortalSessionTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String RETURN = ORIGIN + "/admin/plans";
    private static final String CONFIG = "bpc_SyntheticCardUpdateOnly";

    private final StripeStub stub = new StripeStub();

    private StripeProviderClient client(String returnUrl, String configuration) {
        StripeCredentials credentials = StripeCredentials.fromEnvironment(Map.of(StripeCredentials.ENV_SECRET_KEY, "sk_" + "test_" + "SyntheticR503Portal0000001",
                StripeCredentials.ENV_WEBHOOK_SECRET, "whsec_" + "SyntheticR503Portal0000002")::get);
        return new StripeProviderClient(credentials, new StripeSettings(ORIGIN + "/billing/return", ORIGIN + "/billing/cancelled", ORIGIN, returnUrl, configuration), StripeStub.ACCOUNT, stub,
                Clock.systemUTC());
    }

    private static String session(boolean livemode) {
        return "{\"id\":\"bps_synthetic\",\"object\":\"billing_portal.session\",\"customer\":\"cus_Synthetic1\",\"configuration\":\"" + CONFIG
                + "\",\"livemode\":" + livemode + ",\"return_url\":\"" + RETURN + "\",\"url\":\"https://billing.stripe.com/p/session/test_synthetic\"}";
    }

    @Test
    void createsASessionForTheStoredCustomerWithTheConfiguredPortalAndReturnUrl() {
        stub.on("POST", "/v1/billing_portal/sessions", c -> StripeStub.ok(session(false)));
        ProviderPortalSession s = client(RETURN, CONFIG).createPortalSession("cus_Synthetic1");
        assertThat(s.url()).isEqualTo("https://billing.stripe.com/p/session/test_synthetic");
        StripeStub.Call call = stub.calls("POST", "/v1/billing_portal/sessions").get(0);
        assertThat(call.params()).containsEntry("customer", "cus_Synthetic1").containsEntry("configuration", CONFIG).containsEntry("return_url", RETURN);
        assertThat(call.params()).as("nothing else: no flow, no locale, no subscription").hasSize(3);
        assertThat(call.stripeVersion()).isEqualTo(StripeProviderClient.apiVersion());
    }

    @Test
    void aLiveModeSessionIsRefused() {
        stub.on("POST", "/v1/billing_portal/sessions", c -> StripeStub.ok(session(true)));
        assertThatThrownBy(() -> client(RETURN, CONFIG).createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void incompletePortalSettingsAreRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> client(RETURN, null).createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderRejectedException.class).hasMessageContaining("portal-configuration");
        assertThatThrownBy(() -> client(RETURN, "not_a_configuration").createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderRejectedException.class);
        assertThatThrownBy(() -> client(null, CONFIG).createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderRejectedException.class).hasMessageContaining("portal-return-url");
        assertThatThrownBy(() -> client("https://elsewhere.example/plans", CONFIG).createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderRejectedException.class)
                .hasMessageContaining("trusted origins");
        assertThatThrownBy(() -> client(RETURN, CONFIG).createPortalSession("not_a_customer")).isInstanceOf(ProviderRejectedException.class);
        assertThat(stub.calls()).isEmpty();
    }

    @Test
    void aStripeOutageIsATimeoutAndARefusalHidesStripesMessage() {
        stub.on("POST", "/v1/billing_portal/sessions", c -> StripeStub.timeout());
        assertThatThrownBy(() -> client(RETURN, CONFIG).createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderTimeoutException.class);
        stub.on("POST", "/v1/billing_portal/sessions", c -> StripeStub.error(400, "invalid_request_error", "resource_missing"));
        assertThatThrownBy(() -> client(RETURN, CONFIG).createPortalSession("cus_Synthetic1")).isInstanceOf(ProviderRejectedException.class)
                .hasMessageNotContaining("synthetic resource_missing");
    }
}
