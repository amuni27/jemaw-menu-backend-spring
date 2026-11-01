package com.agafari.com.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * AGF-S2-24: the owner's daily guest menu openings, oldest first, ending with today in the restaurant's zone. Days
 * without openings are included with 0.
 */
public record MenuViewsResponse(String timeZone, int today, List<Day> days) {

    public record Day(LocalDate date, int views) {
    }
}
