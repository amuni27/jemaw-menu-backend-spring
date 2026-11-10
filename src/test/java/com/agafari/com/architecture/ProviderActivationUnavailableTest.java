package com.agafari.com.architecture;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.ProviderActivationReadiness;
import com.agafari.com.billing.VerifiedPaymentRef;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.BillingFlags.MarketFlags;
import com.agafari.com.config.FlagFacts;
import com.agafari.com.config.FlagMatrix;
import com.agafari.com.config.FlagMatrixValidator;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.subscription.SubscriptionLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-02, rewritten by SUB-R4-10 (PO rulings R4-09 D1 and R3). SUB-R4-10 makes the paid commands reachable inside
 * the application, through one path only: the {@code inbox.process} handler → {@code PaymentOutcomeService}. Real PROVIDER
 * activation stays impossible because of an intentional restriction ({@link ProviderActivationReadiness}), not only
 * because provider secrets or adapter beans happen to be missing. The Spring-level half is
 * {@code ProviderActivationReachabilityTest}.
 */
class ProviderActivationUnavailableTest {

    private static final String OUTCOME_SERVICE = "billing/PaymentOutcomeService.java";
    private static final String HANDLER = "billing/InboxProcessHandler.java";
    private static final String EXCEPTIONS = "billing/ExceptionService.java";

    @Test
    void theEntitysFactoriesAreKnown() {
        List<String> factories = Arrays.stream(BusinessSubscription.class.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && !m.isSynthetic() && m.getReturnType() == BusinessSubscription.class)
                .map(Method::getName).toList();
        assertThat(factories).containsExactlyInAnyOrder("manual", "owner", "trial", "provider");
    }

    @Test
    void theEntitysSourceCantBeSet() {
        assertThat(Arrays.stream(BusinessSubscription.class.getMethods()).map(Method::getName)).doesNotContain("setSource");
    }

    @Test
    void theLifecycleCommandsAreKnown() {
        List<String> commands = Arrays.stream(SubscriptionLifecycle.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).map(Method::getName).distinct().toList();
        assertThat(commands).containsExactlyInAnyOrder("startOwnerFree", "recordManual", "requireAdminPeriodEditable", "applyAdminChange", "changeTransitionDate",
                "startTrial", "replace", "expireTrialIfDue", "endGrandfathered", "applyScheduledCancel", "applyScheduledDowngrade", "materialize",
                "activatePaid", "renew", "markPastDue", "replacePaid");
    }

    @Test
    void theEvidencePointerCantBeBuiltOutsideBilling() {
        for (Constructor<?> c : VerifiedPaymentRef.class.getDeclaredConstructors()) assertThat(Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())).as(c.toString()).isFalse();
    }

    @Test
    void onlyThePaymentOutcomeServiceWritesTheLedger() throws IOException {
        assertThat(offenders(code -> {
            String lower = code.toLowerCase();
            return lower.contains("'provider'") || lower.contains("subscriptionsource.provider.name()") || lower.contains("insert into payment")
                    || lower.contains("insert into billing_invoice") || lower.contains("insert into refund_request");
        }, OUTCOME_SERVICE)).isEmpty();
        assertThat(offenders(code -> code.toLowerCase().contains("insert into billing_exception"), EXCEPTIONS)).as("SUB-R4-11: one exception writer").isEmpty();
        assertThat(offenders(code -> code.toLowerCase().contains("insert into subscription_period"), "jpa/repository/BusinessSubscriptionRepository.java")).isEmpty();
    }

    @Test
    void onlyThePaymentOutcomeServiceBuildsEvidenceOrCallsAPaidCommand() throws IOException {
        assertThat(offenders(code -> code.contains("new VerifiedPaymentRef(") || code.contains(".activatePaid(") || code.contains(".renew(") || code.contains(".replacePaid("),
                OUTCOME_SERVICE)).isEmpty();
        assertThat(offenders(code -> code.contains(".markPastDue("), "subscription/SubscriptionLifecycle.java")).as("no production caller of markPastDue yet").isEmpty();
        assertThat(offenders(code -> code.contains("BusinessSubscription.provider("), "subscription/SubscriptionLifecycle.java")).isEmpty();
    }

    @Test
    void onlyTheInboxHandlerAndAnExceptionReplayCallThePaymentOutcomeService() throws IOException {
        assertThat(offenders(code -> withoutComments(code).contains("PaymentOutcomeService"), OUTCOME_SERVICE, HANDLER, EXCEPTIONS)).isEmpty();
        assertThat(offenders(code -> java.util.regex.Pattern.compile("\\.replay\\([^)]").matcher(withoutComments(code)).find(), EXCEPTIONS, "controller/AdminBillingExceptionController.java")).as("SUB-R4-11: replay only from an exception").isEmpty();
    }

    @Test
    void noProductionProviderAdapterOrStrategyBeanExists() throws IOException {
        assertThat(offenders(code -> code.contains("extends GuardedProviderClient") || code.contains("implements PaymentProviderClient"), "billing/port/GuardedProviderClient.java")).isEmpty();
        for (Class<?> strategy : List.of(com.agafari.com.billing.EtBillingStrategy.class, com.agafari.com.billing.UsBillingStrategy.class)) {
            assertThat(strategy.isAnnotationPresent(Component.class) || strategy.isAnnotationPresent(Service.class)).as(strategy + " is not a bean").isFalse();
        }
        assertThat(offenders(code -> code.contains("@Bean") && (code.contains(BillingStrategy.class.getSimpleName()) || code.contains(PaymentProviderClient.class.getSimpleName())))).isEmpty();
    }

    @Test
    void activationReadinessIsAnIntentionalRestrictionWithTheOpenGatesNamed() {
        assertThat(ProviderActivationReadiness.ready()).isFalse();
        assertThat(String.join("|", ProviderActivationReadiness.openGates())).contains("AD-7").contains("R7b").contains("SUB-R4-11").contains("SUB-R4-13")
                .contains("SUB-R4-14").contains("SUB-R4-16").contains("unattributable").contains("Full mutation");
    }

    @Test
    void billingStaysRefusedEvenWhenEveryOtherRequirementIncludingSecretsIsMet() {
        BillingFlags flags = new BillingFlags(true, true, true, new MarketFlags(true, true, false, false), new MarketFlags(true, true, false, false));
        FlagFacts everything = new FlagFacts(true, true, true, true, true, true, true);

        assertThat(FlagMatrix.violations(flags, true, everything)).as("the flag matrix alone would allow it").isEmpty();
        assertThat(FlagMatrixValidator.violations(flags, true, everything)).hasSize(2)
                .allMatch(v -> v.contains("billing-enabled") && v.contains("provider activation readiness") && v.contains("AD-7"));
    }

    @Test
    void theOutcomeServiceIgnoresTheBillingFlagWhileActivationIsNotReady() {
        BillingFlags on = new BillingFlags(true, true, true, new MarketFlags(true, true, false, false), new MarketFlags(true, true, false, false));
        PaymentOutcomeService service = new PaymentOutcomeService(null, null, null, null, null, null, null, null, null, null, on, null);

        assertThat(service.billingActive(Market.US)).isFalse();
        assertThat(service.billingActive(Market.ET)).isFalse();
    }

    @Test
    void theTestOnlySwitchesAreNeverCalledFromProduction() throws IOException {
        assertThat(offenders(code -> code.lines().anyMatch(l -> l.contains("setBillingEnabled(") && !l.contains("public void setBillingEnabled(")))).isEmpty();
    }

    private static String withoutComments(String code) {
        return String.join("\n", code.lines().filter(l -> !l.strip().startsWith("*") && !l.strip().startsWith("/*") && !l.strip().startsWith("//")).toList());
    }

    private static List<Path> offenders(Predicate<String> matches, String... allowed) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).filter(p -> Arrays.stream(allowed).noneMatch(p::endsWith)).filter(p -> {
                try {
                    return matches.test(Files.readString(p));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }
}
