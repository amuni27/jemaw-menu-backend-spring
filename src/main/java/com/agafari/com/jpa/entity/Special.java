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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * AGF-S2-25: a dish shown at a special price during a schedule (dates, weekdays, one daily window; restaurant time).
 * One per dish; deleted with the dish. Free for every restaurant (G7/D7).
 */
@Data
@Entity
@Table(name = "special")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Special extends AuditableEntity implements Scheduled { // AGF-S2-36: shared schedule

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "businessId", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menuItemId", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MenuItem menuItem;

    @Column(name = "specialPrice", nullable = false, precision = 10, scale = 2)
    private BigDecimal specialPrice;

    private String note;

    private String noteAm;

    private LocalDate startsOn;

    private LocalDate endsOn;

    /** MONDAY…SUNDAY in week order; empty = every day */
    @Column(name = "daysOfWeek", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<String> daysOfWeek = new ArrayList<>();

    /** HH:mm, restaurant local time; both or neither */
    @Column(length = 5)
    private String startTime;

    @Column(length = 5)
    private String endTime;

    @Column(name = "isPaused", nullable = false)
    private boolean paused;
}
