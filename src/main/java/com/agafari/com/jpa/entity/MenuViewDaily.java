package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * AGF-S2-24: one restaurant's guest menu openings on one local day (Flyway V9). Written only by MenuViewCounter with
 * plain SQL; mapped so Hibernate validates V9 and the in-memory demo database (built from the entities) has the table.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "menu_view_daily")
@IdClass(MenuViewDaily.Key.class)
public class MenuViewDaily {

    @Id
    @Column(name = "business_id", nullable = false)
    private String businessId;

    @Id
    @Column(name = "view_date", nullable = false)
    private LocalDate viewDate;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String businessId;
        private LocalDate viewDate;
    }
}
