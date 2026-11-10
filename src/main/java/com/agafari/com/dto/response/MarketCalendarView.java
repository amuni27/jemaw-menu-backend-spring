package com.agafari.com.dto.response;

import java.util.List;

/** AGF SUB-R4-12: one market's calendar year; {@code recomputed} is how many open exception deadlines the change moved. */
public record MarketCalendarView(String market, int year, boolean published, List<String> weekendDays, List<Holiday> holidays, Integer recomputed) {

    public record Holiday(String date, String name, String sourceNote) {
    }
}
