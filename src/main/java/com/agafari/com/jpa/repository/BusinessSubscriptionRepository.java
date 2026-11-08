package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.BusinessSubscription;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** AGF E4-M3: insert, read and the two allowed changes; no delete method on purpose (the DB refuses deletes too). */
public interface BusinessSubscriptionRepository extends Repository<BusinessSubscription, String> {

    BusinessSubscription saveAndFlush(BusinessSubscription subscription);

    @Query("select s from BusinessSubscription s where s.businessId = :businessId and s.status <> 'EXPIRED'")
    Optional<BusinessSubscription> findCurrentByBusinessId(String businessId);

    @Query("select s from BusinessSubscription s where s.businessId in :businessIds and s.status <> 'EXPIRED'")
    List<BusinessSubscription> findCurrentByBusinessIds(Collection<String> businessIds);

    List<BusinessSubscription> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    /** AGF E4-M5: trials whose period end has passed but which are still persisted as TRIAL. */
    @Query("select s from BusinessSubscription s where s.status = 'TRIAL' and s.periodEnd <= :now order by s.periodEnd")
    List<BusinessSubscription> findDueTrials(Instant now);

    /** AGF E4-M5: the idempotent expiry used by the job and by expire-on-touch; 1 if this call expired it, else 0. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update business_subscription set status = 'EXPIRED', ended_at = period_end, ended_reason = 'TRIAL_END', updated_at = :now, "
            + "updated_by_user_id = 'system', row_version = row_version + 1 "
            + "where id = :id and status = 'TRIAL' and period_end <= :now", nativeQuery = true)
    int expireIfDue(String id, Instant now);

    boolean existsByBusinessIdAndSource(String businessId, String source);

    /** AGF E4-M6: current non-trial subscriptions (the only ones that can be grandfathered). */
    @Query("select s from BusinessSubscription s where s.status <> 'EXPIRED' and s.source <> 'TRIAL'")
    List<BusinessSubscription> findCurrentNonTrial();

    Optional<BusinessSubscription> findById(String id);

    /** AGF E4-M6: ends a grandfathered subscription at its approved end instant; 1 if this call ended it (idempotent). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update business_subscription set status = 'EXPIRED', ended_at = :endedAt, ended_reason = 'GRANDFATHERED_END', updated_at = :now, updated_by_user_id = 'system', "
            + "row_version = row_version + 1 where id = :id and status <> 'EXPIRED' and row_version = :rowVersion", nativeQuery = true)
    int endGrandfathered(String id, Instant endedAt, Instant now, int rowVersion);

    Optional<BusinessSubscription> findFirstByBusinessIdAndSourceOrderByCreatedAtDesc(String businessId, String source);
}
