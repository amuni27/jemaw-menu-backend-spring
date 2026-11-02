package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.RestaurantAd;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AGF E4-M8: restaurant ads. Owner and guest code use only the business-scoped methods (tenant isolation); there is no
 * unscoped lookup except {@link #findById} for the Super Admin, and no delete.
 */
public interface RestaurantAdRepository extends Repository<RestaurantAd, String> {

    RestaurantAd saveAndFlush(RestaurantAd ad);

    /** Super Admin only. */
    Optional<RestaurantAd> findById(String id);

    Optional<RestaurantAd> findByIdAndBusinessId(String id, String businessId);

    List<RestaurantAd> findAllByBusinessIdOrderByCreatedAtDesc(String businessId);

    @Query("select count(a) from RestaurantAd a where a.businessId = :businessId and a.status <> 'ARCHIVED'")
    long countNonArchived(@Param("businessId") String businessId);

    /** The restaurant's ads servable now, in the stable rotation order (Q-M8-6). */
    @Query("select a from RestaurantAd a where a.businessId = :businessId and a.status = 'ACTIVE' and a.startsAt <= :now "
            + "and (a.endsAt is null or a.endsAt > :now) order by a.startsAt, a.createdAt, a.id")
    List<RestaurantAd> findServable(@Param("businessId") String businessId, @Param("now") Instant now);

    /** Super Admin list, newest first, optionally by restaurant and status. */
    @Query("select a from RestaurantAd a where (:businessId is null or a.businessId = :businessId) and (:status is null or a.status = :status) "
            + "order by a.createdAt desc")
    List<RestaurantAd> findForAdmin(@Param("businessId") String businessId, @Param("status") String status);
}
