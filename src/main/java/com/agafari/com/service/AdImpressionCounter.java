package com.agafari.com.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * AGF E4-M7 (C5, C6): adds one served impression for an ad, a restaurant and the restaurant's local day, with the S2-24
 * pattern (atomic increment; the day's first delivery inserts the row; an insert that loses a race retries the increment).
 * Nothing about the guest is read or stored. Counting never affects the guest menu or the ad: failures are logged and
 * swallowed.
 */
@Component
public class AdImpressionCounter {

    private static final Logger log = LoggerFactory.getLogger(AdImpressionCounter.class);
    private static final String INCREMENT =
            "update ad_impression_daily set impression_count = impression_count + 1 where ad_id = ? and business_id = ? and view_date = ?";
    private static final String FIRST_OF_DAY =
            "insert into ad_impression_daily (ad_id, business_id, view_date, impression_count) values (?, ?, ?, 1)";

    private final JdbcTemplate jdbc;

    public AdImpressionCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String adId, String businessId, LocalDate day) {
        try {
            if (jdbc.update(INCREMENT, adId, businessId, day) == 0) {
                try {
                    jdbc.update(FIRST_OF_DAY, adId, businessId, day);
                } catch (DuplicateKeyException raced) {
                    jdbc.update(INCREMENT, adId, businessId, day); // another delivery inserted the row first
                }
            }
        } catch (RuntimeException e) {
            log.warn("event=ad.impression_not_counted adId={} businessId={} errorClass={}", adId, businessId, e.getClass().getSimpleName());
        }
    }
}
