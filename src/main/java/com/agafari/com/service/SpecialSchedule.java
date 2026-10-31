package com.agafari.com.service;

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

    public enum Status { ACTIVE, SCHEDULED, ENDED, PAUSED }

    public static final List<String> DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    private SpecialSchedule() {
    }

    public static boolean isActive(Special s, Instant now, ZoneId zone) {
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

    public static Status status(Special s, Instant now, ZoneId zone) {
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
    public static String endsTodayAt(Special s, Instant now, ZoneId zone) {
        return isActive(s, now, zone) && hasWindow(s) ? s.getEndTime() : null;
    }

    private static boolean onDay(Special s, LocalDate day) {
        List<String> days = s.getDaysOfWeek();
        return days == null || days.isEmpty() || days.contains(day.getDayOfWeek().name());
    }

    private static boolean hasWindow(Special s) {
        return s.getStartTime() != null && s.getEndTime() != null;
    }

    static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }
}
