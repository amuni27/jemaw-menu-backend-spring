package com.agafari.com.jpa.repository;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, String> {
    long deleteByMenu_Id(String menuId);

    long countByCategory_Id(String categoryId);

    // ✅ secure ownership: item must belong to business through menu
    @EntityGraph(attributePaths = {"menu", "category"})
    Optional<MenuItem> findByIdAndMenu_Business_Id(String itemId, String businessId);

    // ✅ secure list with filters + ordering
    @Query("""
        select i from MenuItem i
        where i.menu.id = :menuId
          and i.menu.business.id = :businessId
          and (:categoryId is null or i.category.id = :categoryId)
          and (:status is null or i.status = :status)
          and (
               :search is null
               or lower(i.name) like lower(concat('%', :search, '%'))
               or lower(i.description) like lower(concat('%', :search, '%'))
          )
        order by i.sortOrder asc, i.createdAt desc
    """)
    List<MenuItem> searchMenuItems(
            String menuId,
            String businessId,
            String categoryId,
            MenuItemStatus status,
            String search
    );

    // max sortOrder per (menu, category)
    @Query("""
        select coalesce(max(i.sortOrder), -1) from MenuItem i
        where i.menu.id = :menuId and i.category.id = :categoryId
    """)
    int maxSortOrder(String menuId, String categoryId);

    @EntityGraph(attributePaths = {"menu", "category"})
    List<MenuItem> findByMenu_IdInAndCategory_IdInAndStatusOrderBySortOrderAscCreatedAtAsc(
            List<String> menuIds,
            List<String> categoryIds,
            MenuItemStatus status
    );

    default List<MenuItem> findAvailableByMenuIdsAndCategoryIds(List<String> menuIds, List<String> categoryIds) {
        return findByMenu_IdInAndCategory_IdInAndStatusOrderBySortOrderAscCreatedAtAsc(
                menuIds, categoryIds, MenuItemStatus.AVAILABLE
        );
    }
}
