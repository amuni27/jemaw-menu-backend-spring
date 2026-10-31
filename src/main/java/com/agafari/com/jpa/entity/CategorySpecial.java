package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * AGF-S2-36: a whole-number percentage off every eligible dish of one category, during a schedule (same rules as
 * dish specials). One per category; deleted with the category. Free for every restaurant (G7/D7).
 */
@Data
@Entity
@Table(name = "category_special")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CategorySpecial extends AuditableEntity implements Scheduled {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "businessId", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoryId", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Category category;

    /** 1–90 */
    @Column(name = "discountPercent", nullable = false)
    private Integer discountPercent;

    private String note;

    private String noteAm;

    private LocalDate startsOn;

    private LocalDate endsOn;

    @Column(name = "daysOfWeek", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<String> daysOfWeek = new ArrayList<>();

    @Column(length = 5)
    private String startTime;

    @Column(length = 5)
    private String endTime;

    @Column(name = "isPaused", nullable = false)
    private boolean paused;
}
