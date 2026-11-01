package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.MonetizationConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** AGF E4-M2: insert and read only (no update or delete methods on purpose). */
public interface MonetizationConfigRepository extends Repository<MonetizationConfig, Integer> {

    MonetizationConfig saveAndFlush(MonetizationConfig config);

    Optional<MonetizationConfig> findTopByOrderByVersionDesc();

    Page<MonetizationConfig> findAllByOrderByVersionDesc(Pageable pageable);
}
