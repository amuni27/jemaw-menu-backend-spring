package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.PlanPrice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** AGF SUB-R3-01: insert and read only; there is no update or delete method on purpose. */
public interface PlanPriceRepository extends Repository<PlanPrice, String> {

    PlanPrice saveAndFlush(PlanPrice price);

    Optional<PlanPrice> findById(String id);

    @Query("select p from PlanPrice p order by p.planCode, p.market, p.interval, p.version")
    List<PlanPrice> findAllVersions();

    @Query("select coalesce(max(p.version), 0) from PlanPrice p where p.planCode = :planCode and p.market = :market and p.interval = :interval")
    int maxVersion(String planCode, String market, String interval);

    /** AGF SUB-R3-02: active versions in effect, the newest of each plan and interval first. */
    @Query("select p from PlanPrice p where p.market = :market and p.planCode in :planCodes and p.active = true and p.validFrom <= :now order by p.planCode, p.interval, p.version desc")
    List<PlanPrice> findInEffect(String market, Collection<String> planCodes, Instant now);

    @Query("select count(p) > 0 from PlanPrice p where p.planCode = :planCode and p.market = :market and p.active = true and p.validFrom <= :now")
    boolean hasActivePrice(String planCode, String market, Instant now);
}
