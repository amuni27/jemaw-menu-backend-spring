package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.CategorySpecial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategorySpecialRepository extends JpaRepository<CategorySpecial, String> {

    List<CategorySpecial> findByBusiness_IdOrderByCreatedAtDesc(String businessId);

    Optional<CategorySpecial> findByIdAndBusiness_Id(String id, String businessId);

    Optional<CategorySpecial> findByCategory_Id(String categoryId);

    /** Guest path (AGF-S2-36 checkpoint B): one query per guest menu, whatever the number of category specials. */
    List<CategorySpecial> findByBusiness_Id(String businessId);
}
