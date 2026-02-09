package com.agafari.com.jpa.entity;


import com.agafari.com.enums.MenuVisibility;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;


@Data
@Entity
@Table(name = "menu")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Menu extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "businessId", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menuTypeId", nullable = false)
    private MenuType menuType;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "isActive", nullable = false)
    private boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MenuVisibility visibility = MenuVisibility.PUBLIC;

    @OneToMany(mappedBy = "menu", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Category> categories = new ArrayList<>();

}

