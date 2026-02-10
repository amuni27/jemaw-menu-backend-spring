package com.agafari.com.jpa.repository;


import com.agafari.com.jpa.entity.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {

    // Load menu to avoid LazyInitialization when mapping
    @EntityGraph(attributePaths = {"menu"})
    List<Category> findByMenu_IdAndMenu_Business_IdOrderBySortOrderAsc(
            String menuId,
            String businessId
    );

    @EntityGraph(attributePaths = {"menu"})
    Optional<Category> findByIdAndMenu_Business_Id(
            String categoryId,
            String businessId
    );

    List<Category> findByIdInAndMenu_IdAndMenu_Business_Id(
            List<String> ids,
            String menuId,
            String businessId
    );

    // category must belong to menu + business
    Optional<Category> findByIdAndMenu_IdAndMenu_Business_Id(String categoryId, String menuId, String businessId);

    // for updates: category must belong to same menu
    Optional<Category> findByIdAndMenu_Id(String categoryId, String menuId);

    @EntityGraph(attributePaths = {"menu"})
    List<Category> findByMenu_IdInAndIsActiveTrueOrderBySortOrderAscCreatedAtAsc(List<String> menuIds);

    default List<Category> findActiveByMenuIds(List<String> menuIds) {
        return findByMenu_IdInAndIsActiveTrueOrderBySortOrderAscCreatedAtAsc(menuIds);
    }
}
