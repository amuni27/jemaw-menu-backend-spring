package com.agafari.com.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * AGF SUB-R4-12 (R-3, R-6, I4; PO G1, G2): business days of a market, read in the restaurant's own time zone. Weekends are
 * configured per market and year (default Saturday and Sunday). Holidays count only in a year marked published; an
 * unpublished year falls back to weekends only and is reported as incomplete, so the SLA never silently grows.
 */
@Component
public class BusinessCalendar {

    public static final Set<DayOfWeek> DEFAULT_WEEKEND = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final int MAX_DAYS_SEARCHED = 370;

    /** @param published only then do {@code holidays} count */
    public record YearRules(Set<DayOfWeek> weekend, Set<LocalDate> holidays, boolean published) {

        public YearRules {
            weekend = Set.copyOf(weekend);
            holidays = Set.copyOf(holidays);
        }
    }

    /** @param calendarIncomplete a year consulted isn't published, so holidays were left out */
    public record SlaDate(Instant due, boolean calendarIncomplete) {
    }

    private final JdbcTemplate jdbc;

    public BusinessCalendar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * G2: the same local time on the second following business date. A local time that doesn't exist that day (a DST gap)
     * moves forward by the gap; one that happens twice (an overlap) takes the earlier offset.
     */
    public static SlaDate twoBusinessDaysAfter(Instant created, ZoneId zone, IntFunction<YearRules> years) {
        ZonedDateTime start = created.atZone(zone);
        LocalDate date = start.toLocalDate();
        LocalTime time = start.toLocalTime();
        Map<Integer, YearRules> seen = new HashMap<>();
        boolean incomplete = false;
        int found = 0;
        for (int i = 0; i < MAX_DAYS_SEARCHED && found < 2; i++) {
            date = date.plusDays(1);
            YearRules rules = seen.computeIfAbsent(date.getYear(), y -> {
                YearRules r = years.apply(y);
                return r != null ? r : new YearRules(DEFAULT_WEEKEND, Set.of(), false);
            });
            if (!rules.published()) incomplete = true;
            if (rules.weekend().contains(date.getDayOfWeek())) continue;
            if (rules.published() && rules.holidays().contains(date)) continue;
            found++;
        }
        if (found < 2) throw new IllegalStateException("The calendar has no business days");
        return new SlaDate(ZonedDateTime.ofLocal(LocalDateTime.of(date, time), zone, null).toInstant(), incomplete);
    }

    public SlaDate twoBusinessDaysAfter(Market market, Instant created, ZoneId zone) {
        return twoBusinessDaysAfter(created, zone, y -> rules(market, y));
    }

    /** A year without a row is unpublished with the default weekend. */
    public YearRules rules(Market market, int year) {
        List<Map<String, Object>> rows = jdbc.queryForList("select published, weekend_days from market_calendar_year where market = ? and year = ?", market.name(), year);
        boolean published = !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("published"));
        Set<DayOfWeek> weekend = rows.isEmpty() ? DEFAULT_WEEKEND : parseWeekend((String) rows.get(0).get("weekend_days"));
        Set<LocalDate> holidays = jdbc.queryForList("select calendar_date from market_calendar_day where market = ? and calendar_date between ? and ?", Date.class,
                market.name(), Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year, 12, 31))).stream().map(Date::toLocalDate).collect(Collectors.toSet());
        return new YearRules(weekend, holidays, published);
    }

    static Set<DayOfWeek> parseWeekend(String value) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(DayOfWeek::valueOf).forEach(days::add);
        return days;
    }
}
