package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.Special;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecialRepository extends JpaRepository<Special, String> {

    List<Special> findByBusiness_IdOrderByCreatedAtDesc(String businessId);

    Optional<Special> findByIdAndBusiness_Id(String id, String businessId);

    Optional<Special> findByMenuItem_Id(String menuItemId);
}
