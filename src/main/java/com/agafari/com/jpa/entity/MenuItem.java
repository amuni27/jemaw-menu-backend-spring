package com.agafari.com.jpa.entity;


import com.agafari.com.enums.ItemStatus;
import com.agafari.com.enums.SpiceLevel;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;


@Data
@Entity
@Table(name = "menu_item")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem extends AuditableEntity {

    @Id
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Object ingredients;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Object allergens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Object tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status = ItemStatus.AVAILABLE;

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

