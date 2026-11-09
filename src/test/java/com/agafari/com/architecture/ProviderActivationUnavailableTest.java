package com.agafari.com.architecture;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.billing.VerifiedPaymentRef;
import com.agafari.com.subscription.SubscriptionLifecycle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-02: V23.2 lets the database accept evidenced PROVIDER rows, so the application must still have no way to
 * create one. SUB-R4-09 adds the paid commands, but they stay unreachable: nothing in production can build their evidence
 * or call them. SUB-R4-10 changes this test, and the PROVIDER grace-end correction remains a gate before any activation.
 */
class ProviderActivationUnavailableTest {

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
    void noProductionCodeWritesTheProviderSourceOrTheLedger() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String code = Files.readString(p).toLowerCase();
                    return code.contains("'provider'") || code.contains("subscriptionsource.provider.name()")
                            || code.contains("insert into payment") || code.contains("insert into billing_invoice")
                            || (code.contains("insert into subscription_period") && !p.endsWith("jpa/repository/BusinessSubscriptionRepository.java"));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(offenders).isEmpty();
        }
    }

    @Test
    void theEvidencePointerCantBeBuiltOutsideBilling() {
        for (Constructor<?> c : VerifiedPaymentRef.class.getDeclaredConstructors()) assertThat(Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())).as(c.toString()).isFalse();
    }

    @Test
    void nothingInProductionBuildsEvidenceOrCallsAPaidCommand() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String code = Files.readString(p);
                    boolean lifecycle = p.endsWith("subscription/SubscriptionLifecycle.java");
                    return code.contains("new VerifiedPaymentRef(") || code.contains(".activatePaid(") || code.contains("lifecycle.renew(") || code.contains(".replacePaid(")
                            || code.contains("lifecycle.markPastDue(") || (!lifecycle && code.contains("BusinessSubscription.provider("));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(offenders).isEmpty();
        }
    }
}
