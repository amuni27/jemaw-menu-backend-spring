package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.Menu;
import com.agafari.com.jpa.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, String> {
    long deleteByMenu_Id(String menuId);

    long countByCategory_Id(String categoryId);
}
