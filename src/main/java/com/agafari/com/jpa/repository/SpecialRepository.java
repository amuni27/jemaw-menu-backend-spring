package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.Special;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpecialRepository extends JpaRepository<Special, String> {

    List<Special> findByBusiness_IdOrderByCreatedAtDesc(String businessId);

    Optional<Special> findByIdAndBusiness_Id(String id, String businessId);

    Optional<Special> findByMenuItem_Id(String menuItemId);

    /** Guest path (checkpoint B): one query, with each dish loaded for the price guard. */
    @Query("select s from Special s join fetch s.menuItem where s.business.id = :businessId")
    List<Special> findWithDishByBusinessId(@Param("businessId") String businessId);
}
