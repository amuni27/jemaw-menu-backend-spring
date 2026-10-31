package com.agafari.com.service;

import com.agafari.com.dto.request.ScheduleInput;
import com.agafari.com.jpa.entity.Scheduled;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AGF-S2-36: merges and validates the note and schedule of a dish or category special (moved unchanged from the S2-25
 * dish special service so both kinds share one set of rules and messages). Omitted/null = unchanged; "" clears a
 * note; clear flags remove dates or the window. Errors are added to the given map in form order.
 */
public final class ScheduleRules {

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private ScheduleRules() {
    }

    public static void apply(Scheduled s, ScheduleInput req, Map<String, String> errors) {
        if (req.getNote() != null) s.setNote(req.getNote().isBlank() ? null : req.getNote().strip());
        if (s.getNote() != null && s.getNote().length() > 255) errors.put("note", "Use at most 255 characters.");
        if (req.getNoteAm() != null) s.setNoteAm(AmharicText.normalize(req.getNoteAm()));
        if (s.getNoteAm() != null && s.getNoteAm().length() > 255) errors.put("noteAm", "Use at most 255 characters.");

        if (Boolean.TRUE.equals(req.getClearStartsOn())) s.setStartsOn(null);
        if (Boolean.TRUE.equals(req.getClearEndsOn())) s.setEndsOn(null);
        if (req.getStartsOn() != null) s.setStartsOn(date(req.getStartsOn(), "startsOn", errors));
        if (req.getEndsOn() != null) s.setEndsOn(date(req.getEndsOn(), "endsOn", errors));
        if (s.getStartsOn() != null && s.getEndsOn() != null && s.getEndsOn().isBefore(s.getStartsOn()) && !errors.containsKey("endsOn")) {
            errors.put("endsOn", "The end date can't be before the start date.");
        }

        if (req.getDaysOfWeek() != null) {
            List<String> days = req.getDaysOfWeek().stream().map(d -> d == null ? "" : d.strip().toUpperCase()).distinct().toList();
            if (!SpecialSchedule.DAYS.containsAll(days)) errors.put("daysOfWeek", "Choose days from Monday to Sunday.");
            else s.setDaysOfWeek(SpecialSchedule.DAYS.stream().filter(days::contains).toList());
        }
        if (s.getDaysOfWeek() == null) s.setDaysOfWeek(List.of());

        if (Boolean.TRUE.equals(req.getClearTimeWindow())) {
            s.setStartTime(null);
            s.setEndTime(null);
        }
        if (req.getStartTime() != null) s.setStartTime(req.getStartTime().strip());
        if (req.getEndTime() != null) s.setEndTime(req.getEndTime().strip());
        timeWindow(s, errors);

        if (req.getPaused() != null) s.setPaused(req.getPaused());
    }

    private static LocalDate date(String value, String field, Map<String, String> errors) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            errors.put(field, "Use a date like 2026-11-30.");
            return null;
        }
    }

    private static void timeWindow(Scheduled s, Map<String, String> errors) {
        String start = s.getStartTime();
        String end = s.getEndTime();
        if (start == null && end == null) return;
        if (start != null && !TIME.matcher(start).matches()) errors.put("startTime", "Use a time like 16:00.");
        if (end != null && !TIME.matcher(end).matches()) errors.put("endTime", "Use a time like 18:00.");
        if (errors.containsKey("startTime") || errors.containsKey("endTime")) return;
        if (start == null) errors.put("startTime", "Add a start time too.");
        else if (end == null) errors.put("endTime", "Add an end time too.");
        else if (end.compareTo(start) <= 0) errors.put("endTime", "The end time must be after the start time.");
    }
}
