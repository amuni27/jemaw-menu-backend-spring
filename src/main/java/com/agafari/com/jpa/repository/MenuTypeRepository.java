package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.MenuType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuTypeRepository extends JpaRepository<MenuType, String> {
    List<MenuType> findAllByOrderByNameAsc();
}
