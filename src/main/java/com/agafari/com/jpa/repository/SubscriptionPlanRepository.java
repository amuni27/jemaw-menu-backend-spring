package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.SubscriptionPlan;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** AGF E4-M3: plans are read-only reference data (seeded by V13). */
public interface SubscriptionPlanRepository extends Repository<SubscriptionPlan, String> {

    List<SubscriptionPlan> findAllByOrderByCodeAsc();

    Optional<SubscriptionPlan> findById(String code);

    SubscriptionPlan save(SubscriptionPlan plan); // H2 demo seed only

    long count();
}
