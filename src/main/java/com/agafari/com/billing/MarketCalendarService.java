package com.agafari.com.billing;

import com.agafari.com.dto.request.MarketCalendarYearRequest;
import com.agafari.com.dto.request.MarketHolidayRequest;
import com.agafari.com.dto.response.MarketCalendarView;
import com.agafari.com.exception.ConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import com.agafari.com.service.MonetizationLocks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AGF SUB-R4-12 (R-6, I4, TA §6.9): the Super Admin's per-market calendar. Each change is one audited transaction under the
 * exclusive configuration lock, so no exception is created with the old calendar while it commits, and it recomputes the
 * deadlines of that market's open exceptions in the same transaction. Holidays are entered when officially announced; no
 * calendar data is seeded or invented here.
 */
@Service
public class MarketCalendarService {

    static final String YEAR_UPDATED = "MARKET_CALENDAR_YEAR_UPDATED";
    static final String HOLIDAY_ADDED = "MARKET_HOLIDAY_ADDED";
    static final String HOLIDAY_REMOVED = "MARKET_HOLIDAY_REMOVED";
    private static final String TARGET = "MARKET_CALENDAR";
    private static final int MIN_YEAR = 2000, MAX_YEAR = 2100;

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final ExceptionSla sla;
    private final MonetizationLocks locks;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public MarketCalendarService(JdbcTemplate jdbc, BusinessCalendar calendar, ExceptionSla sla, MonetizationLocks locks, AdminAuditService audit,
                                 PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.sla = sla;
        this.locks = locks;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    public MarketCalendarView get(String market, int year) {
        return view(market(market), year(year), null);
    }

    public MarketCalendarView updateYear(String marketCode, int year, MarketCalendarYearRequest request, String adminId, String adminEmail) {
        Market market = market(marketCode);
        year(year);
        if (request == null || request.published() == null) throw new FieldValidationException(Map.of("published", "Required"));
        Set<DayOfWeek> weekend = weekend(request.weekendDays());
        String days = weekend.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
        return transaction.execute(s -> {
            locks.configExclusive();
            jdbc.update("insert into market_calendar_year (market, year, published, weekend_days) values (?, ?, ?, ?) "
                    + "on conflict (market, year) do update set published = excluded.published, weekend_days = excluded.weekend_days", market.name(), year, request.published(), days);
            String actor = LogMasking.maskEmail(adminEmail);
            audit.success(YEAR_UPDATED, adminId, actor, TARGET, market + "-" + year, "published=" + request.published() + " weekend=" + days);
            return view(market, year, sla.recompute(market, adminId, actor));
        });
    }

    public MarketCalendarView addHoliday(String marketCode, MarketHolidayRequest request, String adminId, String adminEmail) {
        Market market = market(marketCode);
        if (request == null) throw new FieldValidationException(Map.of("date", "Required"));
        LocalDate date = date(request.date());
        String name = text(request.name(), "name", 255, true);
        String note = text(request.sourceNote(), "sourceNote", 1024, false);
        return transaction.execute(s -> {
            locks.configExclusive();
            int added = jdbc.update("insert into market_calendar_day (market, calendar_date, kind, name, source_note) values (?, ?, 'PUBLIC_HOLIDAY', ?, ?) on conflict do nothing",
                    market.name(), Date.valueOf(date), name, note);
            if (added == 0) throw new ConflictException("This holiday is already in the calendar");
            String actor = LogMasking.maskEmail(adminEmail);
            audit.success(HOLIDAY_ADDED, adminId, actor, TARGET, market + "-" + date, name);
            return view(market, date.getYear(), sla.recompute(market, adminId, actor));
        });
    }

    public MarketCalendarView removeHoliday(String marketCode, String dateText, String adminId, String adminEmail) {
        Market market = market(marketCode);
        LocalDate date = date(dateText);
        return transaction.execute(s -> {
            locks.configExclusive();
            if (jdbc.update("delete from market_calendar_day where market = ? and calendar_date = ?", market.name(), Date.valueOf(date)) == 0) {
                throw new NotFoundException("No such holiday");
            }
            String actor = LogMasking.maskEmail(adminEmail);
            audit.success(HOLIDAY_REMOVED, adminId, actor, TARGET, market + "-" + date, null);
            return view(market, date.getYear(), sla.recompute(market, adminId, actor));
        });
    }

    private MarketCalendarView view(Market market, int year, Integer recomputed) {
        BusinessCalendar.YearRules rules = calendar.rules(market, year);
        List<MarketCalendarView.Holiday> holidays = jdbc.query("select calendar_date, name, source_note from market_calendar_day where market = ? and calendar_date between ? and ? order by calendar_date",
                (rs, n) -> new MarketCalendarView.Holiday(rs.getDate("calendar_date").toLocalDate().toString(), rs.getString("name"), rs.getString("source_note")),
                market.name(), Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year, 12, 31)));
        return new MarketCalendarView(market.name(), year, rules.published(), rules.weekend().stream().sorted().map(DayOfWeek::name).toList(), holidays, recomputed);
    }

    private static Market market(String code) {
        return Market.fromCountry(code).orElseThrow(() -> new NotFoundException("Unknown market"));
    }

    private static int year(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) throw new FieldValidationException(Map.of("year", "Between " + MIN_YEAR + " and " + MAX_YEAR));
        return year;
    }

    /** I4: at least one weekend day (V23.3) and at least one business day. */
    private static Set<DayOfWeek> weekend(List<String> names) {
        if (names == null || names.isEmpty()) throw new FieldValidationException(Map.of("weekendDays", "At least one weekend day"));
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String n : names) {
            try {
                days.add(DayOfWeek.valueOf(n == null ? "" : n.strip().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException(Map.of("weekendDays", "Days are MONDAY … SUNDAY"));
            }
        }
        if (days.size() == DayOfWeek.values().length) throw new FieldValidationException(Map.of("weekendDays", "At least one business day is required"));
        return days;
    }

    private static LocalDate date(String text) {
        try {
            LocalDate d = LocalDate.parse(text == null ? "" : text);
            year(d.getYear());
            return d;
        } catch (DateTimeParseException e) {
            throw new FieldValidationException(Map.of("date", "An ISO date (yyyy-MM-dd)"));
        }
    }

    private static String text(String value, String field, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new FieldValidationException(Map.of(field, "Required"));
            return null;
        }
        String v = value.strip();
        if (v.length() > max) throw new FieldValidationException(Map.of(field, "At most " + max + " characters"));
        return v;
    }
}
