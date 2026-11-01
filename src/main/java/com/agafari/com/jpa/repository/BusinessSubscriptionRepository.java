package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.BusinessSubscription;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

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
}
