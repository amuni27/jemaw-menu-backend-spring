package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.TrialIntent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AGF E4-M5 recovery: insert, read and conditional updates that only touch a PENDING intent (0 rows when it was already
 * resolved); no delete method on purpose (the DB refuses deletes too).
 */
public interface TrialIntentRepository extends Repository<TrialIntent, String> {

    TrialIntent saveAndFlush(TrialIntent intent);

    Optional<TrialIntent> findById(String businessId);

    /** The daily retry batch: never attempted first, then least recently attempted, so failing intents can't starve others. */
    @Query(value = "select business_id from trial_intent where status = 'PENDING' order by last_attempt_at nulls first, created_at limit :limit",
            nativeQuery = true)
    List<String> findRetryBatch(int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update trial_intent set status = :status, resolution = :resolution, subscription_id = :subscriptionId, resolved_at = :now, "
            + "row_version = row_version + 1 where business_id = :businessId and status = 'PENDING'", nativeQuery = true)
    int resolve(String businessId, String status, String resolution, String subscriptionId, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update trial_intent set failed_attempts = failed_attempts + 1, last_attempt_at = :now, last_error = :error, "
            + "row_version = row_version + 1 where business_id = :businessId and status = 'PENDING'", nativeQuery = true)
    int recordFailure(String businessId, Instant now, String error);

    /** Waiting for something else (a pending plan request): moves the intent behind the others without counting a failure. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update trial_intent set last_attempt_at = :now, row_version = row_version + 1 "
            + "where business_id = :businessId and status = 'PENDING'", nativeQuery = true)
    int defer(String businessId, Instant now);

    @Query("select i from TrialIntent i where i.status = 'PENDING' order by i.createdAt")
    List<TrialIntent> findPending(Pageable pageable);

    @Query("select count(i) from TrialIntent i where i.status = 'PENDING'")
    long countPending();

    @Query("select count(i) from TrialIntent i where i.status = 'PENDING' and i.failedAttempts >= :attempts")
    long countPendingWithFailures(int attempts);
}
