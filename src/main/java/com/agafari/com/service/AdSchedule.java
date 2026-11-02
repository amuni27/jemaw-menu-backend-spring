package com.agafari.com.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AGF E4-M7 D-M7-1 (Option A): an ad schedule is an exact local date and time in an explicitly chosen IANA time zone,
 * stored as a UTC instant. A local time that doesn't exist (clocks move forward) is refused; one that occurs twice (clocks
 * move back) needs an explicit offset. Reading back gives the same local time and offset, so saving unchanged keeps the
 * exact instant.
 */
public final class AdSchedule {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx");
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    private AdSchedule() {
    }

    /** The zone, or null (with a field error) when it isn't a known region zone. */
    public static ZoneId zone(String timeZone, Map<String, String> errors) {
        if (timeZone == null || !ZONES.contains(timeZone)) {
            errors.put("timeZone", "Choose a time zone");
            return null;
        }
        return ZoneId.of(timeZone);
    }

    /** The instant for {@code local} (and {@code offset} if ambiguous) in {@code zone}, or null with a field error. */
    public static Instant toInstant(String field, String offsetField, String local, String offset, ZoneId zone, Map<String, String> errors) {
        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(local, LOCAL);
        } catch (DateTimeParseException e) {
            errors.put(field, "Use a date and time (YYYY-MM-DDTHH:mm)");
            return null;
        }
        if (zone == null) return null;
        List<ZoneOffset> valid = zone.getRules().getValidOffsets(dateTime);
        if (valid.isEmpty()) {
            errors.put(field, "This time doesn't exist in " + zone.getId() + " (clocks move forward); choose another time");
            return null;
        }
        if (offset == null || offset.isBlank()) {
            if (valid.size() == 1) return dateTime.atOffset(valid.get(0)).toInstant();
            errors.put(field, "This time occurs twice in " + zone.getId() + "; choose the offset ("
                    + valid.stream().map(AdSchedule::offset).collect(Collectors.joining(" or ")) + ")");
            return null;
        }
        try {
            ZoneOffset chosen = ZoneOffset.of(offset);
            if (valid.contains(chosen)) return dateTime.atOffset(chosen).toInstant();
        } catch (RuntimeException ignored) {
            // falls through to the error below
        }
        errors.put(offsetField, "This offset isn't valid for this time in " + zone.getId());
        return null;
    }

    public static String local(Instant instant, ZoneId zone) {
        return instant == null ? null : LOCAL.format(instant.atZone(zone).toLocalDateTime());
    }

    public static String offset(Instant instant, ZoneId zone) {
        return instant == null ? null : offset(zone.getRules().getOffset(instant));
    }

    private static String offset(ZoneOffset offset) {
        return OFFSET.format(offset);
    }
}
