package com.agafari.com.jpa.entity;

import com.agafari.com.enums.DayOfWeekEnum;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;


@Data
@Entity
@Table(name = "business_hours")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessHours extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "businessId", nullable = false)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(name = "dayOfWeek", nullable = false)
    private DayOfWeekEnum dayOfWeek;

    @Column(name = "isOpen", nullable = false)
    private boolean isOpen = true;

    // stored as text in DB (you can change later to LocalTime)
    @Column(name = "startTime")
    private String startTime;

    @Column(name = "endTime")
    private String endTime;
}
