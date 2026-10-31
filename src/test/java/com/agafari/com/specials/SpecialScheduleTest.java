package com.agafari.com.specials;

import com.agafari.com.jpa.entity.Special;
import com.agafari.com.service.SpecialSchedule;
import com.agafari.com.service.SpecialSchedule.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S2-25 (D-S2-25-8): a special is active when not paused, today (restaurant time) is within its dates and an
 * included weekday, and now is within its daily window [start, end). Evaluated in the restaurant's time zone (S2-26).
 */
class SpecialScheduleTest {

    private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Special special(String startsOn, String endsOn, List<String> days, String start, String end) {
        Special s = new Special();
        s.setStartsOn(startsOn == null ? null : LocalDate.parse(startsOn));
        s.setEndsOn(endsOn == null ? null : LocalDate.parse(endsOn));
        s.setDaysOfWeek(days);
        s.setStartTime(start);
        s.setEndTime(end);
        return s;
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void withoutAScheduleItIsAlwaysActive() {
        Special s = special(null, null, List.of(), null, null);
        assertThat(SpecialSchedule.isActive(s, at("2026-11-04T09:00:00Z"), ADDIS)).isTrue();
        assertThat(SpecialSchedule.status(s, at("2026-11-04T09:00:00Z"), ADDIS)).isEqualTo(Status.ACTIVE);
    }

    @Test
    void datesAreInclusiveInTheRestaurantsLocalDate() {
        Special s = special("2026-11-02", "2026-11-04", List.of(), null, null);
        // 2026-11-01 21:30 UTC is already 2026-11-02 00:30 in Addis Ababa
        assertThat(SpecialSchedule.isActive(s, at("2026-11-01T21:30:00Z"), ADDIS)).isTrue();
        assertThat(SpecialSchedule.isActive(s, at("2026-11-01T20:30:00Z"), ADDIS)).isFalse();
        assertThat(SpecialSchedule.status(s, at("2026-11-01T20:30:00Z"), ADDIS)).isEqualTo(Status.SCHEDULED);
        assertThat(SpecialSchedule.isActive(s, at("2026-11-04T20:59:00Z"), ADDIS)).isTrue();  // 23:59 on the last day
        assertThat(SpecialSchedule.isActive(s, at("2026-11-04T21:00:00Z"), ADDIS)).isFalse(); // 00:00 the day after
        assertThat(SpecialSchedule.status(s, at("2026-11-04T21:00:00Z"), ADDIS)).isEqualTo(Status.ENDED);
    }

    @Test
    void weekdaysAndTheDailyWindowStartInclusiveEndExclusive() {
        Special happyHour = special(null, null, List.of("MONDAY", "FRIDAY"), "16:00", "18:00");
        // 2026-11-06 is a Friday; Addis = UTC+3
        assertThat(SpecialSchedule.isActive(happyHour, at("2026-11-06T13:00:00Z"), ADDIS)).isTrue();   // 16:00
        assertThat(SpecialSchedule.isActive(happyHour, at("2026-11-06T14:59:00Z"), ADDIS)).isTrue();   // 17:59
        assertThat(SpecialSchedule.isActive(happyHour, at("2026-11-06T15:00:00Z"), ADDIS)).isFalse();  // 18:00
        assertThat(SpecialSchedule.isActive(happyHour, at("2026-11-06T12:59:00Z"), ADDIS)).isFalse();  // 15:59
        assertThat(SpecialSchedule.isActive(happyHour, at("2026-11-05T13:30:00Z"), ADDIS)).isFalse();  // Thursday
        assertThat(SpecialSchedule.status(happyHour, at("2026-11-05T13:30:00Z"), ADDIS)).isEqualTo(Status.SCHEDULED);
        assertThat(SpecialSchedule.endsTodayAt(happyHour, at("2026-11-06T13:30:00Z"), ADDIS)).isEqualTo("18:00");
        assertThat(SpecialSchedule.endsTodayAt(special(null, null, List.of(), null, null), at("2026-11-06T13:30:00Z"), ADDIS)).isNull();
    }

    @Test
    void theWindowFollowsNewYorkLocalTimeAcrossDaylightSaving() {
        Special lunch = special(null, null, List.of(), "11:00", "14:00");
        assertThat(SpecialSchedule.isActive(lunch, at("2026-01-14T16:30:00Z"), NEW_YORK)).isTrue();  // 11:30 EST
        assertThat(SpecialSchedule.isActive(lunch, at("2026-07-15T15:30:00Z"), NEW_YORK)).isTrue();  // 11:30 EDT
        assertThat(SpecialSchedule.isActive(lunch, at("2026-07-15T18:30:00Z"), NEW_YORK)).isFalse(); // 14:30 EDT
        assertThat(SpecialSchedule.isActive(lunch, at("2026-01-14T16:30:00Z"), ADDIS)).isFalse();    // 19:30 in Addis
    }

    @Test
    void pausedWinsAndTheLastWindowOfTheLastDayEnds() {
        Special s = special(null, "2026-11-06", List.of(), "16:00", "18:00");
        s.setPaused(true);
        assertThat(SpecialSchedule.isActive(s, at("2026-11-06T13:30:00Z"), ADDIS)).isFalse();
        assertThat(SpecialSchedule.status(s, at("2026-11-06T13:30:00Z"), ADDIS)).isEqualTo(Status.PAUSED);
        s.setPaused(false);
        assertThat(SpecialSchedule.status(s, at("2026-11-06T15:30:00Z"), ADDIS)).isEqualTo(Status.ENDED); // 18:30 on the end date
        assertThat(SpecialSchedule.status(s, at("2026-11-06T10:00:00Z"), ADDIS)).isEqualTo(Status.SCHEDULED); // 13:00, later today
    }
}
