package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * AGF E4-M7: served impressions per ad, restaurant and restaurant-local day (Flyway V17; the S2-24 aggregate approach).
 * Written only by {@code AdImpressionCounter} with plain SQL; mapped so the in-memory demo schema has the table.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "ad_impression_daily")
@IdClass(AdImpressionDaily.Key.class)
public class AdImpressionDaily {

    @Id
    @Column(name = "ad_id", nullable = false, length = 36)
    private String adId;

    @Id
    @Column(name = "business_id", nullable = false)
    private String businessId;

    @Id
    @Column(name = "view_date", nullable = false)
    private LocalDate viewDate;

    @Column(name = "impression_count", nullable = false)
    private int impressionCount;

    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String adId;
        private String businessId;
        private LocalDate viewDate;
    }
}
