package com.agafari.com.architecture;

import com.agafari.com.architecture.WriterBoundaryScanner.Violation;
import com.agafari.com.architecture.fixtures.BoundaryViolations;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.subscription.SubscriptionLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.data.jpa.repository.Modifying;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.agafari.com.architecture.WriterBoundaryScanner.classesScanned;
import static com.agafari.com.architecture.WriterBoundaryScanner.lockViolations;
import static com.agafari.com.architecture.WriterBoundaryScanner.scanClass;
import static com.agafari.com.architecture.WriterBoundaryScanner.scanTree;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R2-05 (BD-9): the single-writer rule, enforced on compiled production classes with the bytecode reader that ships
 * inside Spring (no new dependency). Only {@link SubscriptionLifecycle} may create, change or end a subscription; the entity
 * and {@link BusinessSubscriptionRepository} are its persistence boundary. Reading stays free.
 */
class ArchitectureBoundaryTest {

    private static Path productionClasses() throws Exception {
        return Path.of(BusinessSubscription.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    // ---- the codebase

    @Test
    void theProductionCodeHasNoWriterBoundaryViolations() throws Exception {
        List<Violation> violations = scanTree(productionClasses());
        assertThat(violations).as("Subscriptions may be written only through SubscriptionLifecycle:%n%s",
                violations.stream().map(Violation::toString).collect(Collectors.joining(System.lineSeparator()))).isEmpty();
    }

    @Test
    void theScanReallyCoversTheProductionClasses() throws Exception {
        assertThat(classesScanned(productionClasses())).isGreaterThan(200);
    }

    @Test
    void everyLifecycleWriteChecksItsLockFirstAndOnlyTrialExpiryUsesTheNarrowerCheck() {
        // accepted qualification D1 (SUB-R2-04): trial expiry keeps its restaurant-only lock; no other command may
        List<Violation> violations = lockViolations(SubscriptionLifecycle.class, "expireTrialIfDue");
        assertThat(violations).as(violations.toString()).isEmpty();
    }

    // ---- every bypass is detected

    static Stream<Arguments> bypasses() {
        return Stream.of(
                Arguments.of(BoundaryViolations.CreatesWithConstructor.class, "CREATE"),
                Arguments.of(BoundaryViolations.CreatesWithManualFactory.class, "CREATE"),
                Arguments.of(BoundaryViolations.CreatesWithOwnerFactory.class, "CREATE"),
                Arguments.of(BoundaryViolations.CreatesWithTrialFactory.class, "CREATE"),
                Arguments.of(BoundaryViolations.EndsDirectly.class, "MUTATE"),
                Arguments.of(BoundaryViolations.EndsTrialDirectly.class, "MUTATE"),
                Arguments.of(BoundaryViolations.ChangesPeriodEnd.class, "MUTATE"),
                Arguments.of(BoundaryViolations.ChangesTransitionDate.class, "MUTATE"),
                Arguments.of(BoundaryViolations.EndsThroughAMethodReference.class, "MUTATE"),
                Arguments.of(BoundaryViolations.EndsInALambda.class, "MUTATE"),
                Arguments.of(BoundaryViolations.SavesThroughTheRepository.class, "REPOSITORY"),
                Arguments.of(BoundaryViolations.ExpiresThroughTheRepository.class, "REPOSITORY"),
                Arguments.of(BoundaryViolations.EndsThroughTheRepository.class, "REPOSITORY"),
                Arguments.of(BoundaryViolations.UpdatesStatusWithJdbc.class, "SQL"),
                Arguments.of(BoundaryViolations.InsertsWithJdbc.class, "SQL"),
                Arguments.of(BoundaryViolations.DeletesWithJdbc.class, "SQL"),
                Arguments.of(BoundaryViolations.WritesThePeriodTableWithJdbc.class, "SQL"),
                Arguments.of(BoundaryViolations.BuildsUpdateByConcatenation.class, "SQL"),
                Arguments.of(BoundaryViolations.OtherRepositoryWithNativeUpdate.class, "SQL"),
                Arguments.of(BoundaryViolations.OtherRepositoryWithJpqlUpdate.class, "SQL"),
                Arguments.of(BoundaryViolations.PersistsThroughTheEntityManager.class, "PERSISTENCE"),
                Arguments.of(BoundaryViolations.MergesThroughTheEntityManager.class, "PERSISTENCE"),
                Arguments.of(BoundaryViolations.WritesAFieldByReflection.class, "PERSISTENCE"),
                Arguments.of(BoundaryViolations.ConstructsVerifiedPaymentRef.class, "VERIFIED_PAYMENT"));
    }

    @ParameterizedTest
    @MethodSource("bypasses")
    void aBypassIsReportedWithAClearMessage(Class<?> bypass, String rule) {
        List<Violation> violations = scanClass(bypass);
        assertThat(violations).as(bypass.getSimpleName()).anyMatch(v -> v.rule().equals(rule));
        assertThat(violations).allSatisfy(v -> assertThat(v.toString()).contains(rule).contains(bypass.getName().replace('.', '/')));
    }

    @Test
    void readingIsNotAViolation() {
        assertThat(scanClass(BoundaryViolations.OnlyReads.class)).isEmpty();
    }

    @Test
    void persistenceInAClassUnrelatedToSubscriptionsIsNotAViolation() {
        assertThat(scanClass(BoundaryViolations.UnrelatedPersistence.class)).isEmpty();
    }

    // ---- provider SDK rule (vacuous in production until R5; generated classes because the SDK isn't a dependency)

    private static byte[] classCallingStripe(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/stripe/Stripe", "getApiBase", "()Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void aProviderSdkReferenceOutsideItsAdapterIsReported() {
        assertThat(WriterBoundaryScanner.scanBytes(classCallingStripe("com/agafari/com/service/PaysDirectly"))).anyMatch(v -> v.rule().equals("PROVIDER_SDK"));
    }

    @Test
    void aProviderSdkReferenceInsideItsAdapterIsAllowed() {
        assertThat(WriterBoundaryScanner.scanBytes(classCallingStripe("com/agafari/com/billing/stripe/StripeClient"))).isEmpty();
    }

    // ---- the lock rule itself

    @Test
    void aLifecycleWriteWithoutALockIsReported() {
        assertThat(lockViolations(BoundaryViolations.WritesWithoutALock.class, "expireTrialIfDue")).anyMatch(v -> v.rule().equals("LOCK"));
    }

    @Test
    void aLifecycleWriteBeforeItsLockIsReported() {
        assertThat(lockViolations(BoundaryViolations.WritesBeforeTheLock.class, "expireTrialIfDue")).anyMatch(v -> v.rule().equals("LOCK"));
    }

    @Test
    void theRestaurantOnlyCheckOutsideTheExceptionIsReported() {
        assertThat(lockViolations(BoundaryViolations.RestaurantOnlyOutsideTheException.class, "expireTrialIfDue")).anyMatch(v -> v.rule().equals("LOCK"));
    }

    @Test
    void theApprovedShapeIncludingTheTrialExpiryExceptionPasses() {
        assertThat(lockViolations(BoundaryViolations.ApprovedLifecycleShape.class, "expireTrialIfDue")).isEmpty();
    }

    // ---- new members must be classified, so a future writer can't slip past the scanner

    @Test
    void everyPublicEntityMethodIsEitherAReadOrADetectedMutator() {
        Set<String> reads = Set.of("equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait");
        for (Method m : BusinessSubscription.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            boolean read = m.getName().startsWith("get") || m.getName().startsWith("is") || m.getName().startsWith("effective") || reads.contains(m.getName());
            assertThat(read || m.getReturnType() == void.class)
                    .as("%s returns a value but doesn't look like a read; classify it in WriterBoundaryScanner", m).isTrue();
        }
    }

    @Test
    void everyRepositoryMethodIsEitherAReadOrADetectedWrite() {
        for (Method m : BusinessSubscriptionRepository.class.getDeclaredMethods()) {
            boolean write = m.isAnnotationPresent(Modifying.class) || m.getName().matches("(save|delete|remove).*");
            boolean read = m.getName().matches("(find|exists|count|get).*");
            assertThat(write || read).as("%s is neither a known read nor a detected write", m).isTrue();
        }
        assertThat(Arrays.stream(BusinessSubscriptionRepository.class.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Modifying.class)).map(Method::getName))
                .containsExactlyInAnyOrder("expireIfDue", "endGrandfathered");
    }
}
