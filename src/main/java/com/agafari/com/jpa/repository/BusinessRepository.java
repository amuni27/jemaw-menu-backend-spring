package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, String> {
    Optional<Business> findByCustomSubdomain(String customSubdomain);

    @Query("select b from Business b where b.owner.id = :ownerId")
    Optional<Business> findByOwnerId(String ownerId);

    @Query("""
        select b from Business b
        where b.id = :id
    """)
    Optional<Business> findPublicById(String id);

    /** AGF E4-M3: Super Admin restaurant search by name or address part (case-insensitive). */
    @Query("select b from Business b where lower(b.name) like :pattern or lower(b.customSubdomain) like :pattern")
    Page<Business> search(String pattern, Pageable pageable);
}
