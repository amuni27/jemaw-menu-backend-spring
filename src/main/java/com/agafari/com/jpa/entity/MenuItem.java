package com.agafari.com.jpa.entity;


import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


@Data
@Entity
@Table(name = "menu_item")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menuId", nullable = false)
    private Menu menu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoryId", nullable = false)
    private Category category;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    private Integer calories;

    @Column(name = "imageUrl")
    private String imageUrl;


    @Column(name = "ingredients", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> ingredients;

    @Column(name = "allergens", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> allergens = new ArrayList<>();

    @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> tags = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MenuItemStatus status = MenuItemStatus.AVAILABLE;

    @Column(name = "isFeatured", nullable = false)
    private boolean isFeatured = false;

    @Column(name = "prepTimeMinutes")
    private Integer prepTimeMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "spiceLevel")
    private SpiceLevel spiceLevel;

    @Column(name = "sortOrder", nullable = false)
    private int sortOrder = 0;
}

