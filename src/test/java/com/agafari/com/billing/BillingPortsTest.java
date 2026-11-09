package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.fake.FakeProviderClient.Script;
import com.agafari.com.billing.fake.FakeStrategy;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.CheckoutRequest;
import com.agafari.com.billing.port.CheckoutSession;
import com.agafari.com.billing.port.CurrentState;
import com.agafari.com.billing.port.ExpireResult;
import com.agafari.com.billing.port.GuardedProviderClient;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSessionRequest;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.billing.port.ScheduledChangeView;
import com.agafari.com.billing.port.VerifiedEvent;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AGF SUB-R4-04: ports, guard, registry, shells and the fake's scripts. */
class BillingPortsTest {

    private static final Money TEN = new Money(1000, Currency.ETB);
    private static final Map<String, String> META = Map.of("refund_request_id", "r1");
    private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");

    private final FakeProviderClient fake = new FakeProviderClient();

    private static ProviderSessionRequest session() {
        return new ProviderSessionRequest("c1", TEN, T);
    }

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // ---- AC1 and NC-19

    static Stream<Arguments> clientCalls() {
        return Stream.<Map.Entry<String, Consumer<PaymentProviderClient>>>of(
                Map.entry("capabilities", PaymentProviderClient::capabilities),
                Map.entry("createSession", c -> c.createSession(session(), "k")),
                Map.entry("expireSession", c -> c.expireSession("s", "k")),
                Map.entry("verifyPayment", c -> c.verifyPayment("p")),
                Map.entry("submitRefund", c -> c.submitRefund("p", TEN, "k", META)),
                Map.entry("findRefund", c -> c.findRefund("p", "r1")),
                Map.entry("listSince", c -> c.listSince(T, RecordType.PAYMENT)),
                Map.entry("signatureVerifier", PaymentProviderClient::signatureVerifier)
        ).map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientCalls")
    void everyClientMethodThrowsInsideATransaction(String name, Consumer<PaymentProviderClient> call) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> call.accept(fake)).isInstanceOf(ProviderCallInTransactionException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientCalls")
    void everyClientMethodRunsOutsideATransaction(String name, Consumer<PaymentProviderClient> call) {
        assertThatCode(() -> call.accept(fake)).doesNotThrowAnyException();
    }

    @Test
    void theGuardCoversEveryMethodOfThePort() {
        List<String> tested = clientCalls().map(a -> (String) a.get()[0]).toList();
        assertThat(Arrays.stream(PaymentProviderClient.class.getDeclaredMethods()).map(Method::getName)).containsExactlyInAnyOrderElementsOf(tested);
    }

    @Test
    void theGuardedMethodsCantBeOverridden() {
        for (Method m : PaymentProviderClient.class.getDeclaredMethods()) {
            try {
                assertThat(Modifier.isFinal(GuardedProviderClient.class.getMethod(m.getName(), m.getParameterTypes()).getModifiers())).as(m.getName()).isTrue();
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void everyProviderClientExtendsTheGuard() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(PaymentProviderClient.class));
        var found = scanner.findCandidateComponents("com.agafari.com");
        assertThat(found).isNotEmpty();
        for (var candidate : found) {
            assertThat(GuardedProviderClient.class).as(candidate.getBeanClassName()).isAssignableFrom(Class.forName(candidate.getBeanClassName()));
        }
    }

    // ---- AC2: the registry

    private static BillingStrategyRegistry registry(JdbcTemplate jdbc, BillingStrategy... strategies) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < strategies.length; i++) beans.addBean("s" + i, strategies[i]);
        return new BillingStrategyRegistry(beans.getBeanProvider(BillingStrategy.class), jdbc);
    }

    private static JdbcTemplate marketsOf(String business, List<String> markets) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(any(String.class), eq(String.class), eq(business))).thenReturn(markets);
        return jdbc;
    }

    @Test
    void aMarketResolvesToItsOwnStrategy() {
        FakeStrategy us = new FakeStrategy(Market.US), et = new FakeStrategy(Market.ET);
        BillingStrategyRegistry r = registry(mock(JdbcTemplate.class), us, et);
        assertThat(r.forMarket(Market.US)).isSameAs(us);
        assertThat(r.forMarket(Market.ET)).isSameAs(et);
    }

    @Test
    void aMarketWithoutAStrategyIsRefused() {
        BillingStrategyRegistry r = registry(mock(JdbcTemplate.class), new FakeStrategy(Market.US));
        assertThatThrownBy(() -> r.forMarket(Market.ET)).isInstanceOf(IllegalStateException.class).hasMessageContaining("No billing strategy for market ET");
    }

    @Test
    void aNullMarketIsRefused() {
        assertThatThrownBy(() -> registry(mock(JdbcTemplate.class)).forMarket(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("No billing strategy");
    }

    @Test
    void twoStrategiesForOneMarketAreRefused() {
        assertThatThrownBy(() -> registry(mock(JdbcTemplate.class), new FakeStrategy(Market.US), new FakeStrategy(Market.US)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Two billing strategies for market US");
    }

    @Test
    void theRestaurantsBillingAccountChoosesTheStrategy() {
        FakeStrategy us = new FakeStrategy(Market.US), et = new FakeStrategy(Market.ET);
        assertThat(registry(marketsOf("b", List.of("ET")), us, et).forBusiness("b")).isSameAs(et);
        assertThat(registry(marketsOf("b", List.of("US")), us, et).forBusiness("b")).isSameAs(us);
    }

    @Test
    void aRestaurantWithoutABillingAccountIsRefused() {
        BillingStrategyRegistry r = registry(marketsOf("b", List.of()), new FakeStrategy(Market.ET));
        assertThatThrownBy(() -> r.forBusiness("b")).isInstanceOf(IllegalStateException.class).hasMessageContaining("no billing account");
    }

    @Test
    void anUnknownStoredMarketIsRefused() {
        BillingStrategyRegistry r = registry(marketsOf("b", List.of("GB")), new FakeStrategy(Market.ET));
        assertThatThrownBy(() -> r.forBusiness("b")).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown market");
    }

    @Test
    void conflictingBillingAccountMarketsAreRefused() {
        BillingStrategyRegistry r = registry(marketsOf("b", List.of("ET", "US")), new FakeStrategy(Market.ET), new FakeStrategy(Market.US));
        assertThatThrownBy(() -> r.forBusiness("b")).isInstanceOf(IllegalStateException.class).hasMessageContaining("more than one market");
    }

    @Test
    void aRegistryWithNoStrategiesStarts() {
        assertThatCode(() -> registry(mock(JdbcTemplate.class))).doesNotThrowAnyException();
    }

    // ---- shells

    @Test
    void theShellsDeclareTheirMarketAndDelegate() {
        BillingStrategy us = new UsBillingStrategy(new FakeStrategy(Market.US)), et = new EtBillingStrategy(new FakeStrategy(Market.ET));
        assertThat(us.market()).isEqualTo(Market.US);
        assertThat(et.market()).isEqualTo(Market.ET);
        CurrentState state = new CurrentState("s1", "MODEL_4");
        assertThat(et.planCheckout(new CheckoutRequest("b", "p"), state).replacesSubscriptionId()).isEqualTo("s1");
        assertThat(et.planCheckout(new CheckoutRequest("b", "p"), state).amount().currency()).isEqualTo(Currency.ETB);
        assertThat(et.sessionRequest(new CheckoutSession("c", "b", CheckoutKind.NEW, TEN, T)).checkoutId()).isEqualTo("c");
        assertThat(et.interpret(new VerifiedEvent("P", "e", "t", "r", T), state)).isEmpty();
        assertThat(us.onScheduleChange(new ScheduledChangeView("c", "CANCEL", null, T)).providerScheduleRequired()).isTrue();
        assertThat(et.onScheduleChange(new ScheduledChangeView("c", "CANCEL", null, T)).providerScheduleRequired()).isFalse();
    }

    @Test
    void aShellRefusesAnotherMarketsDelegate() {
        assertThatThrownBy(() -> new UsBillingStrategy(new FakeStrategy(Market.ET))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("market");
        assertThatThrownBy(() -> new EtBillingStrategy(new FakeStrategy(Market.US))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("market");
    }

    @Test
    void aShellExposesItsDelegatesProviderAndClient() {
        FakeProviderClient client = new FakeProviderClient();
        BillingStrategy et = new EtBillingStrategy(new FakeStrategy(Market.ET, "FAKE_ET", client));
        assertThat(et.provider()).isEqualTo("FAKE_ET");
        assertThat(et.client()).isSameAs(client);
    }

    @Test
    void checkoutServiceTouchesNothingWithBillingOff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        com.agafari.com.config.BillingFlags flags = new com.agafari.com.config.BillingFlags(false, false, false,
                new com.agafari.com.config.BillingFlags.MarketFlags(false, false, false, false), new com.agafari.com.config.BillingFlags.MarketFlags(false, false, false, false));
        CheckoutService service = new CheckoutService(jdbc, null, null, null, null, null, null, null, null, mock(org.springframework.transaction.PlatformTransactionManager.class), flags, null);
        assertThatThrownBy(() -> service.create("b", "o", "p", "k")).isInstanceOf(com.agafari.com.exception.NotFoundException.class);
        assertThatThrownBy(() -> service.get("b", "c")).isInstanceOf(com.agafari.com.exception.NotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void theConfigurationStepNeverTouchesTheDatabaseOnH2() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        com.agafari.com.service.MonetizationLocks locks = mock(com.agafari.com.service.MonetizationLocks.class);
        when(locks.onPostgres()).thenReturn(false);
        new CheckoutCancellation(jdbc, null, null, locks).requestForConfiguration(List.of("MODEL_2"), true);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void aShellNeedsADelegate() {
        assertThatThrownBy(() -> new EtBillingStrategy(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- value types

    @Test
    void capabilitiesDefaultToNone() {
        ProviderCapabilities none = ProviderCapabilities.none();
        assertThat(List.of(none.idempotentCreate(), none.sessionExpire(), none.idempotentRefund(), none.refundLookup())).containsOnly(false);
        assertThat(none.idempotencyWindow()).isZero();
    }

    @Test
    void anIdempotentCapabilityNeedsAWindow() {
        assertThatThrownBy(() -> new ProviderCapabilities(false, false, true, Duration.ZERO, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderCapabilities(true, false, false, Duration.ZERO, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderCapabilities(false, false, false, null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyASuccessfulPaymentHasAPaymentTime() {
        assertThatThrownBy(() -> new VerifiedPayment("r", ProviderPaymentStatus.SUCCEEDED, TEN, null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedPayment("r", ProviderPaymentStatus.PENDING, TEN, T, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedPayment("r", ProviderPaymentStatus.FAILED, TEN, T, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVerifiedPaymentNeedsAReferenceStatusAndAmount() {
        assertThatThrownBy(() -> new VerifiedPayment(" ", ProviderPaymentStatus.PENDING, TEN, null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedPayment("r", null, TEN, null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedPayment("r", ProviderPaymentStatus.SUCCEEDED, null, T, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPendingOutcomeIsAValidUnresolvedAnswer() {
        VerifiedPayment p = new VerifiedPayment("r", ProviderPaymentStatus.PENDING, null, null, null);
        assertThat(p.status()).isEqualTo(ProviderPaymentStatus.PENDING);
        assertThat(p.metadata()).isEmpty();
    }

    // ---- AC3: the fake's scripts

    @Test
    void successVerifiesAndCarriesThePaymentTime() {
        VerifiedPayment p = fake.verifyPayment("p");
        assertThat(p.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(p.paidAt()).isEqualTo(FakeProviderClient.PAID_AT);
    }

    @Test
    void declineRefusesAndVerifiesAsFailed() {
        fake.script(Script.DECLINE);
        assertThat(fake.verifyPayment("p").status()).isEqualTo(ProviderPaymentStatus.FAILED);
        assertThatThrownBy(() -> fake.createSession(session(), "k")).isInstanceOf(ProviderRejectedException.class);
        assertThatThrownBy(() -> fake.submitRefund("p", TEN, "k", META)).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void delayIsPendingThenSucceeded() {
        fake.script(Script.DELAY).delayedFetches(2);
        assertThat(fake.verifyPayment("p").status()).isEqualTo(ProviderPaymentStatus.PENDING);
        assertThat(fake.verifyPayment("p").status()).isEqualTo(ProviderPaymentStatus.PENDING);
        assertThat(fake.verifyPayment("p").status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(fake.verifyPayment("other").status()).isEqualTo(ProviderPaymentStatus.PENDING);
    }

    @Test
    void timeoutAfterAcceptTookEffectAndARetryReturnsTheSameSession() {
        fake.script(Script.TIMEOUT_AFTER_ACCEPT);
        assertThatThrownBy(() -> fake.createSession(session(), "k")).isInstanceOf(ProviderTimeoutException.class);
        assertThat(fake.sessionsCreated()).isEqualTo(1);
        assertThat(fake.createSession(session(), "k").providerSessionRef()).isEqualTo("fake_session_1");
        assertThat(fake.sessionsCreated()).isEqualTo(1);
    }

    @Test
    void timeoutAfterAcceptOnARefundCreatesOneRefund() {
        fake.script(Script.TIMEOUT_AFTER_ACCEPT);
        assertThatThrownBy(() -> fake.submitRefund("p", TEN, "k", META)).isInstanceOf(ProviderTimeoutException.class);
        assertThat(fake.submitRefund("p", TEN, "k", META).providerRefundRef()).isEqualTo("fake_refund_1");
        assertThat(fake.refundsCreated()).isEqualTo(1);
    }

    private FakeProviderClient withRecords() {
        return fake.record(new ProviderRecord("old", RecordType.PAYMENT, T)).record(new ProviderRecord("new", RecordType.PAYMENT, T.plusSeconds(60)))
                .record(new ProviderRecord("inv", RecordType.INVOICE, T)).record(new ProviderRecord("early", RecordType.PAYMENT, T.minusSeconds(60)));
    }

    private List<String> listed() {
        return fake.listSince(T, RecordType.PAYMENT).stream().map(ProviderRecord::providerRef).toList();
    }

    @Test
    void aListingIsFilteredAndOrdered() {
        withRecords();
        assertThat(listed()).containsExactly("old", "new");
    }

    @Test
    void duplicateReturnsEveryRecordTwice() {
        withRecords().script(Script.DUPLICATE);
        assertThat(listed()).containsExactly("old", "new", "old", "new");
    }

    @Test
    void outOfOrderReturnsNewestFirst() {
        withRecords().script(Script.OUT_OF_ORDER);
        assertThat(listed()).containsExactly("new", "old");
    }

    @Test
    void expiryReportsAnAlreadyCompleteSession() {
        assertThat(fake.expireSession("s", "k")).isEqualTo(ExpireResult.EXPIRED);
        assertThat(fake.script(Script.ALREADY_COMPLETE_ON_EXPIRE).expireSession("s", "k")).isEqualTo(ExpireResult.ALREADY_COMPLETE);
    }

    @Test
    void lookupLagFindsNothingEvenWhenTheRefundExists() {
        fake.submitRefund("p", TEN, "k", META);
        fake.script(Script.LOOKUP_LAG).laggedLookups(1);
        assertThat(fake.findRefund("p", "r1")).isEmpty();
        assertThat(fake.findRefund("p", "r1")).isPresent();
    }

    @Test
    void anAbsentRefundIsNotFound() {
        assertThat(fake.findRefund("p", "none")).isEmpty();
    }

    @Test
    void withIdempotentCreateTheSameKeyReturnsTheSameSession() {
        assertThat(fake.createSession(session(), "k")).isEqualTo(fake.createSession(session(), "k"));
        assertThat(fake.createSession(session(), "other").providerSessionRef()).isEqualTo("fake_session_2");
    }

    @Test
    void withoutIdempotentCreateTheSameKeyCreatesAnotherSession() {
        fake.capabilities(new ProviderCapabilities(false, true, true, Duration.ofHours(1), true));
        fake.createSession(session(), "k");
        fake.createSession(session(), "k");
        assertThat(fake.sessionsCreated()).isEqualTo(2);
    }

    @Test
    void withoutIdempotentRefundTheSameKeyCreatesAnotherRefund() {
        fake.capabilities(new ProviderCapabilities(true, true, false, Duration.ofHours(1), true));
        fake.submitRefund("p", TEN, "k", META);
        fake.submitRefund("p", TEN, "k", META);
        assertThat(fake.refundsCreated()).isEqualTo(2);
    }

    @Test
    void unconfirmedCapabilitiesAreUnsupported() {
        fake.capabilities(ProviderCapabilities.none());
        assertThatThrownBy(() -> fake.expireSession("s", "k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fake.findRefund("p", "r1")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(fake.capabilities()).isEqualTo(ProviderCapabilities.none());
    }

    @Test
    void theSignatureCoversTheRawBody() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String good = FakeProviderClient.sign(body);
        assertThat(fake.signatureVerifier().verify(Map.of(FakeProviderClient.SIGNATURE_HEADER, good), body)).isTrue();
        assertThat(fake.signatureVerifier().verify(Map.of(FakeProviderClient.SIGNATURE_HEADER, good), "{ }".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(fake.signatureVerifier().verify(Map.of(), body)).isFalse();
    }

    @Test
    void theFakeIsDeterministic() {
        FakeProviderClient a = new FakeProviderClient().script(Script.DELAY), b = new FakeProviderClient().script(Script.DELAY);
        assertThat(List.of(a.verifyPayment("p"), a.verifyPayment("p"), a.createSession(session(), "k"))).isEqualTo(List.of(b.verifyPayment("p"), b.verifyPayment("p"), b.createSession(session(), "k")));
    }
}
