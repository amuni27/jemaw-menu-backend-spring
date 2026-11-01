package com.agafari.com.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * AGF-S2-24 (D-S2-24-1 option A): adds one guest menu opening to the restaurant's count for its local day. An atomic
 * increment; the day's first opening inserts the row, and an insert that loses a race retries the increment, so
 * concurrent openings on any number of instances are all counted. Plain SQL that PostgreSQL and the H2 demo both run.
 * Nothing about the guest is read or stored. Counting must never affect the guest menu: failures are logged and swallowed.
 */
@Component
public class MenuViewCounter {

    private static final Logger log = LoggerFactory.getLogger(MenuViewCounter.class);
    private static final String INCREMENT =
            "update menu_view_daily set view_count = view_count + 1 where business_id = ? and view_date = ?";
    private static final String FIRST_OF_DAY =
            "insert into menu_view_daily (business_id, view_date, view_count) values (?, ?, 1)";

    private final JdbcTemplate jdbc;
    private volatile Clock clock = Clock.systemUTC(); // replaceable in tests (as in S2-26)

    public MenuViewCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The restaurant's local date now (S2-26 zone; Addis Ababa when unset). */
    public LocalDate today(String timeZone) {
        return LocalDate.now(clock.withZone(TimeZoneRules.zoneId(timeZone)));
    }

    public void record(String businessId, String timeZone) {
        try {
            LocalDate day = today(timeZone);
            if (jdbc.update(INCREMENT, businessId, day) == 0) {
                try {
                    jdbc.update(FIRST_OF_DAY, businessId, day);
                } catch (DuplicateKeyException raced) {
                    jdbc.update(INCREMENT, businessId, day); // another opening inserted the row first
                }
            }
        } catch (RuntimeException e) {
            log.warn("Menu view not counted for business {}: {}", businessId, e.getMessage());
        }
    }
}
