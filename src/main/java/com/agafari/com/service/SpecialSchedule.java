package com.agafari.com.service;

import com.agafari.com.jpa.entity.Scheduled;
import com.agafari.com.jpa.entity.Special;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * AGF-S2-25 (D-S2-25-8): when a special applies. Active = not paused, today (restaurant date) within the inclusive
 * dates and an included weekday (none listed = every day), and now within the daily window [start, end).
 */
public final class SpecialSchedule {

    /** NOT_A_DISCOUNT (checkpoint B): the dish's regular price is now at or below the special price */
    public enum Status { ACTIVE, SCHEDULED, ENDED, PAUSED, NOT_A_DISCOUNT }

    public static final List<String> DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    private SpecialSchedule() {
    }

    public static boolean isActive(Scheduled s, Instant now, ZoneId zone) {
        if (s.isPaused()) return false;
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        if (s.getStartsOn() != null && today.isBefore(s.getStartsOn())) return false;
        if (s.getEndsOn() != null && today.isAfter(s.getEndsOn())) return false;
        if (!onDay(s, today)) return false;
        if (!hasWindow(s)) return true;
        int minute = local.getHour() * 60 + local.getMinute();
        return minute >= minutes(s.getStartTime()) && minute < minutes(s.getEndTime());
    }

    /**
     * PO-approved read-time guard (checkpoint B): a special applies only while it is active and its price is lower than
     * the dish's current regular price; otherwise guests see the regular price. Nothing is changed in the database.
     */
    public static boolean applies(Special s, Instant now, ZoneId zone) {
        return isActive(s, now, zone) && isDiscount(s);
    }

    public static boolean isDiscount(Special s) {
        return s.getMenuItem() == null || s.getMenuItem().getPrice() == null || s.getSpecialPrice() == null
                || s.getSpecialPrice().compareTo(s.getMenuItem().getPrice()) < 0;
    }

    public static Status status(Special s, Instant now, ZoneId zone) {
        Status schedule = scheduleStatus(s, now, zone);
        return (schedule == Status.ACTIVE || schedule == Status.SCHEDULED) && !isDiscount(s) ? Status.NOT_A_DISCOUNT : schedule;
    }

    /**
     * When the current period ends: today's window end, or the next local midnight when the special doesn't continue
     * tomorrow (last day, or tomorrow not an included weekday); null when it doesn't end today or doesn't apply.
     */
    public static Instant endsAt(Special s, Instant now, ZoneId zone) {
        return applies(s, now, zone) ? periodEnd(s, now, zone) : null;
    }

    /** AGF-S2-36: end of the current period for any schedule (category specials have no price guard). */
    public static Instant endsAt(Scheduled s, Instant now, ZoneId zone) {
        return isActive(s, now, zone) ? periodEnd(s, now, zone) : null;
    }

    private static Instant periodEnd(Scheduled s, Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        if (hasWindow(s)) return today.atTime(minutes(s.getEndTime()) / 60, minutes(s.getEndTime()) % 60).atZone(zone).toInstant();
        LocalDate tomorrow = today.plusDays(1);
        boolean lastDay = today.equals(s.getEndsOn()) || !onDay(s, tomorrow);
        return lastDay ? tomorrow.atStartOfDay(zone).toInstant() : null;
    }

    /** The schedule's own status (no price guard); shared with category specials (AGF-S2-36). */
    public static Status scheduleStatus(Scheduled s, Instant now, ZoneId zone) {
        if (s.isPaused()) return Status.PAUSED;
        if (isActive(s, now, zone)) return Status.ACTIVE;
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        LocalDate end = s.getEndsOn();
        if (end != null) {
            if (today.isAfter(end)) return Status.ENDED;
            if (today.equals(end)) {
                boolean windowOver = hasWindow(s) && local.getHour() * 60 + local.getMinute() >= minutes(s.getEndTime());
                if (!onDay(s, today) || windowOver) return Status.ENDED;
            }
        }
        return Status.SCHEDULED;
    }

    /** The local end time today ("HH:mm") while active with a daily window; otherwise null. */
    public static String endsTodayAt(Scheduled s, Instant now, ZoneId zone) {
        return isActive(s, now, zone) && hasWindow(s) ? s.getEndTime() : null;
    }

    private static boolean onDay(Scheduled s, LocalDate day) {
        List<String> days = s.getDaysOfWeek();
        return days == null || days.isEmpty() || days.contains(day.getDayOfWeek().name());
    }

    private static boolean hasWindow(Scheduled s) {
        return s.getStartTime() != null && s.getEndTime() != null;
    }

    static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }
}
