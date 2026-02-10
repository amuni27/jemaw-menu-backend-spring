package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.Menu;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuRepository extends JpaRepository<Menu, String> {

    List<Menu> findByBusiness_IdOrderByCreatedAtDesc(String businessId);

    Optional<Menu> findByIdAndBusiness_Id(String id, String businessId);

    boolean existsByIdAndBusiness_Id(String id, String businessId);


    @EntityGraph(attributePaths = {"menuType"})
    List<Menu> findByBusiness_IdAndIsActiveTrue(String businessId);

    default List<Menu> findActiveMenusWithMenuType(String businessId) {
        return findByBusiness_IdAndIsActiveTrue(businessId);
    }
}
