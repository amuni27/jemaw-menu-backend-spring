package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.PlanRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** AGF E4-M4: insert, read and resolve; no delete method on purpose (the DB refuses deletes too). */
public interface PlanRequestRepository extends Repository<PlanRequest, String> {

    PlanRequest saveAndFlush(PlanRequest request);

    Optional<PlanRequest> findById(String id);

    @Query("select r from PlanRequest r where r.businessId = :businessId and r.status = 'PENDING'")
    Optional<PlanRequest> findPendingByBusinessId(String businessId);

    Page<PlanRequest> findByStatusOrderByRequestedAtDesc(String status, Pageable pageable);
}
