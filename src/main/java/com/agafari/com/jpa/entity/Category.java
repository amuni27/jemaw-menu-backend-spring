package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "category")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menuId", nullable = false)
    private Menu menu;

    @Column(nullable = false)
    private String name;

    @Column(name = "sortOrder", nullable = false)
    private int sortOrder = 0;

    @Column(name = "isActive", nullable = false)
    private boolean isActive = true;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MenuItem> items = new ArrayList<>();
}

