package com.agafari.com.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.agafari.com.jobs.JobFailurePolicy.Kind.PERMANENT;
import static com.agafari.com.jobs.JobFailurePolicy.Kind.TRANSIENT;
import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R2-08: backoff, classification, limits and canonical hashing. */
class JobPolicyTest {

    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");
    private static final Duration CAP = Duration.ofHours(72);
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void backoffFollowsTheApprovedStepsAndStaysAtTheLast() {
        assertThat(List.of(1, 2, 3, 4, 5, 6, 40).stream().map(JobFailurePolicy::backoff).toList()).containsExactly(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(6), Duration.ofHours(6));
    }

    @Test
    void failuresAreClassified() {
        assertThat(JobFailurePolicy.classify(new TransientJobException("x", null))).isEqualTo(TRANSIENT);
        assertThat(JobFailurePolicy.classify(new PermanentJobException("x", null))).isEqualTo(PERMANENT);
        assertThat(JobFailurePolicy.classify(new IOException("unknown"))).isEqualTo(PERMANENT);
    }

    @Test
    void financialTypesAreTheApprovedOnes() {
        for (String t : List.of("refund.execute", "checkout.create", "checkout.expire", "inbox.process")) assertThat(JobFailurePolicy.isFinancial(t)).as(t).isTrue();
        for (String t : List.of("notification.send", "period-end.persist", "inbox.processor")) assertThat(JobFailurePolicy.isFinancial(t)).as(t).isFalse();
    }

    @Test
    void permanentAndUnknownFailuresRetryUntilTheEighthClaim() {
        JobFailurePolicy.Decision seventh = JobFailurePolicy.decide(PERMANENT, 7, T, T, CAP);
        assertThat(seventh.dead()).isFalse();
        assertThat(seventh.retryAfter()).isEqualTo(Duration.ofHours(6));
        assertThat(JobFailurePolicy.decide(PERMANENT, 8, T, T, CAP).dead()).isTrue();
    }

    @Test
    void transientFailuresStopAtTheEighthClaimEvenBeforeTheCap() {
        JobFailurePolicy.Decision seventh = JobFailurePolicy.decide(TRANSIENT, 7, T, T.plusSeconds(1), CAP);
        assertThat(seventh.dead()).isFalse();
        assertThat(seventh.retryAfter()).isEqualTo(Duration.ofHours(6));
        assertThat(JobFailurePolicy.decide(TRANSIENT, 8, T, T.plusSeconds(1), CAP).dead()).isTrue();
    }

    @Test
    void transientFailuresRetryUntilJustBeforeTheCap() {
        assertThat(JobFailurePolicy.decide(TRANSIENT, 3, T, T.plus(CAP).minusNanos(1), CAP).dead()).isFalse();
    }

    @Test
    void transientFailuresGoDeadExactlyAtTheCap() {
        assertThat(JobFailurePolicy.decide(TRANSIENT, 3, T, T.plus(CAP), CAP).dead()).isTrue();
    }

    @Test
    void capsFollowTheApprovedPolicy() {
        assertThat(JobFailurePolicy.transientCap("refund.execute", null)).isEqualTo(Duration.ofHours(72));
        assertThat(JobFailurePolicy.transientCap("refund.execute", Duration.ofHours(1))).as("financial is fixed").isEqualTo(Duration.ofHours(72));
        assertThat(JobFailurePolicy.transientCap("notification.send", null)).isEqualTo(Duration.ofHours(24));
        assertThat(JobFailurePolicy.transientCap("notification.send", Duration.ofHours(48))).as("a handler may declare its own").isEqualTo(Duration.ofHours(48));
    }

    @Test
    void theDefaultNonFinancialCapEndsExactlyAtTwentyFourHours() {
        Duration cap = JobFailurePolicy.transientCap("t", null);
        assertThat(JobFailurePolicy.decide(TRANSIENT, 2, T, T.plus(cap).minusNanos(1), cap).dead()).isFalse();
        assertThat(JobFailurePolicy.decide(TRANSIENT, 2, T, T.plus(cap), cap).dead()).isTrue();
    }

    @Test
    void theHashIgnoresKeyOrderAtEveryDepth() {
        String a = PayloadHash.of(mapper.readTree("{\"a\":1,\"b\":{\"x\":[1,{\"p\":1,\"q\":2}],\"y\":\"s\"}}"));
        String b = PayloadHash.of(mapper.readTree("{\"b\":{\"y\":\"s\",\"x\":[1,{\"q\":2,\"p\":1}]},\"a\":1}"));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void theHashSeesValueAndArrayOrderDifferences() {
        String base = PayloadHash.of(mapper.readTree("{\"a\":[1,2]}"));
        assertThat(PayloadHash.of(mapper.readTree("{\"a\":[2,1]}"))).isNotEqualTo(base);
        assertThat(PayloadHash.of(mapper.readTree("{\"a\":[1,3]}"))).isNotEqualTo(base);
        assertThat(PayloadHash.of(mapper.readTree("{\"a\":\"1\"}"))).isNotEqualTo(PayloadHash.of(mapper.readTree("{\"a\":1}")));
    }

    @Test
    void theCanonicalFormIsCompactAndSorted() {
        assertThat(PayloadHash.canonical(mapper.readTree("{ \"b\" : \"é\\\"x\", \"a\" : null }"))).isEqualTo("{\"a\":null,\"b\":\"é\\\"x\"}");
    }

    @Test
    void theGuardAllowsACallOutsideATransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        ProviderCallGuard.assertNoTransaction();
    }
}
