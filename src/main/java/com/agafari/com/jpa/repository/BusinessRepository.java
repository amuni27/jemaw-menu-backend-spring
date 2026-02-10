package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

}
