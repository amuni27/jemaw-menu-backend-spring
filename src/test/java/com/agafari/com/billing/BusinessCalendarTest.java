package com.agafari.com.billing;

import com.agafari.com.billing.BusinessCalendar.SlaDate;
import com.agafari.com.billing.BusinessCalendar.YearRules;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R4-12: the business-day arithmetic (G1, G2, R-6, I4) and the due-date rule (I2, G4). Pure: no database or clock.
 * Grace values here are fixtures only; AD-7 is open.
 */
class BusinessCalendarTest {

    private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Set<DayOfWeek> SAT_SUN = Set.of(SATURDAY, SUNDAY);

    private static Instant at(ZoneId zone, String local) {
        return LocalDateTime.parse(local).atZone(zone).toInstant();
    }

    private static IntFunction<YearRules> published(Set<DayOfWeek> weekend, LocalDate... holidays) {
        return y -> new YearRules(weekend, Set.of(holidays), true);
    }

    // ---- AC1 and G2 ----

    @Test
    void ac1AFridayAfternoonWithAMondayHolidayIsDueWednesdayAtTheSameTime() {
        SlaDate d = BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-13T16:00"), ADDIS, published(SAT_SUN, LocalDate.parse("2026-11-16")));

        assertThat(d.due()).isEqualTo(at(ADDIS, "2026-11-18T16:00"));
        assertThat(d.calendarIncomplete()).isFalse();
    }

    @Test
    void withoutTheHolidayItIsDueTuesday() {
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-13T16:00"), ADDIS, published(SAT_SUN)).due()).isEqualTo(at(ADDIS, "2026-11-17T16:00"));
    }

    @Test
    void aWeekdayStartCountsTheTwoFollowingBusinessDates() {
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-10T09:15"), ADDIS, published(SAT_SUN)).due()).isEqualTo(at(ADDIS, "2026-11-12T09:15"));
    }

    @Test
    void aWeekendStartCountsFromTheFollowingBusinessDates() {
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-14T10:00"), ADDIS, published(SAT_SUN)).due()).as("Saturday").isEqualTo(at(ADDIS, "2026-11-17T10:00"));
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-15T23:59"), ADDIS, published(SAT_SUN)).due()).as("Sunday").isEqualTo(at(ADDIS, "2026-11-17T23:59"));
    }

    @Test
    void aHolidayStartAndConsecutiveHolidaysAreSkipped() {
        IntFunction<YearRules> rules = published(SAT_SUN, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-17"));
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-16T08:00"), ADDIS, rules).due()).isEqualTo(at(ADDIS, "2026-11-19T08:00"));
    }

    @Test
    void theLocalDateDecidesInTheRestaurantsZoneNotUtc() {
        // 22:30 UTC on Friday is already Saturday 01:30 in Addis Ababa
        Instant created = Instant.parse("2026-11-13T22:30:00Z");
        assertThat(BusinessCalendar.twoBusinessDaysAfter(created, ADDIS, published(SAT_SUN)).due()).isEqualTo(at(ADDIS, "2026-11-17T01:30"));
        assertThat(BusinessCalendar.twoBusinessDaysAfter(created, NEW_YORK, published(SAT_SUN)).due()).isEqualTo(at(NEW_YORK, "2026-11-17T17:30"));
    }

    @Test
    void twoUsRestaurantsInDifferentZonesGetTheirOwnLocalDeadline() {
        Instant created = Instant.parse("2026-11-13T04:30:00Z"); // Thursday 23:30 in New York, Thursday 22:30 in Chicago
        assertThat(BusinessCalendar.twoBusinessDaysAfter(created, NEW_YORK, published(SAT_SUN)).due()).isEqualTo(at(NEW_YORK, "2026-11-16T23:30"));
        assertThat(BusinessCalendar.twoBusinessDaysAfter(created, CHICAGO, published(SAT_SUN)).due()).isEqualTo(at(CHICAGO, "2026-11-16T22:30"));
    }

    // ---- AC3: unpublished years ----

    @Test
    void ac3AnUnpublishedYearUsesWeekendsOnlyAndIsFlaggedIncomplete() {
        IntFunction<YearRules> unpublished = y -> new YearRules(SAT_SUN, Set.of(LocalDate.parse("2026-11-16")), false);

        SlaDate d = BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-13T16:00"), ADDIS, unpublished);

        assertThat(d.due()).as("the holiday of an unpublished year doesn't count").isEqualTo(at(ADDIS, "2026-11-17T16:00"));
        assertThat(d.calendarIncomplete()).isTrue();
    }

    @Test
    void aDeadlineCrossingIntoAnUnpublishedYearIsFlaggedIncomplete() {
        Map<Integer, YearRules> years = Map.of(2026, new YearRules(SAT_SUN, Set.of(), true), 2027, new YearRules(SAT_SUN, Set.of(LocalDate.parse("2027-01-01")), false));

        SlaDate d = BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-12-30T12:00"), ADDIS, years::get);

        assertThat(d.due()).isEqualTo(at(ADDIS, "2027-01-01T12:00"));
        assertThat(d.calendarIncomplete()).isTrue();
    }

    // ---- AC5: configurable weekends ----

    @Test
    void ac5WeekendDaysAreConfigurablePerMarket() {
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-12T16:00"), ADDIS, published(Set.of(SUNDAY))).due()).as("Sunday only")
                .isEqualTo(at(ADDIS, "2026-11-14T16:00"));
        assertThat(BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-12T16:00"), ADDIS, published(Set.of(FRIDAY, SATURDAY))).due()).as("Friday and Saturday")
                .isEqualTo(at(ADDIS, "2026-11-16T16:00"));
    }

    @Test
    void aCalendarWithNoWorkingDayIsRefused() {
        assertThatThrownBy(() -> BusinessCalendar.twoBusinessDaysAfter(at(ADDIS, "2026-11-12T16:00"), ADDIS, published(Set.of(DayOfWeek.values()))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- G2: DST ----

    @Test
    void theLocalTimeIsKeptAcrossAUsDaylightSavingChange() {
        Instant created = at(NEW_YORK, "2026-03-06T16:00"); // Friday, EST
        Instant due = BusinessCalendar.twoBusinessDaysAfter(created, NEW_YORK, published(SAT_SUN)).due();

        assertThat(due).isEqualTo(at(NEW_YORK, "2026-03-10T16:00")).isEqualTo(Instant.parse("2026-03-10T20:00:00Z")); // EDT
        assertThat(Duration.between(created, due)).as("one hour shorter than four days").isEqualTo(Duration.ofHours(95));
    }

    @Test
    void aLocalTimeThatDoesntExistMovesForwardByTheGap() {
        // weekend Friday only, so Sunday 8 March 2026 (spring forward in New York) is the second business date
        Instant due = BusinessCalendar.twoBusinessDaysAfter(at(NEW_YORK, "2026-03-05T02:30"), NEW_YORK, published(Set.of(FRIDAY))).due();

        assertThat(due).isEqualTo(Instant.parse("2026-03-08T07:30:00Z")); // 03:30 EDT
    }

    @Test
    void anAmbiguousLocalTimeTakesTheEarlierOffset() {
        // Sunday 1 November 2026 01:30 happens twice in New York
        Instant due = BusinessCalendar.twoBusinessDaysAfter(at(NEW_YORK, "2026-10-29T01:30"), NEW_YORK, published(Set.of(FRIDAY))).due();

        assertThat(due).isEqualTo(Instant.parse("2026-11-01T05:30:00Z")); // 01:30 EDT, the first occurrence
    }

    // ---- AC2, I2 and G4: the due-date rule ----

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    private static final SlaDate SLA = new SlaDate(NOW.plus(Duration.ofDays(2)), false);

    @Test
    void ac2GraceEndingIn30HoursMakesItDueIn6Hours() {
        ExceptionSla.Due d = ExceptionSla.decide(NOW, SLA, NOW.plus(Duration.ofHours(30)), false, "ET", "Africa/Addis_Ababa");

        assertThat(d.dueAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(d.immediate()).isFalse();
    }

    @Test
    void ac2GraceEndingIn10HoursMakesItDueNowWithImmediatePriority() {
        ExceptionSla.Due d = ExceptionSla.decide(NOW, SLA, NOW.plus(Duration.ofHours(10)), false, "ET", "Africa/Addis_Ababa");

        assertThat(d.dueAt()).isEqualTo(NOW);
        assertThat(d.immediate()).isTrue();
    }

    @Test
    void exactly24HoursOfGraceLeftIsDueNow() {
        ExceptionSla.Due d = ExceptionSla.decide(NOW, SLA, NOW.plus(Duration.ofHours(24)), false, "ET", "Africa/Addis_Ababa");

        assertThat(d.dueAt()).isEqualTo(NOW);
        assertThat(d.immediate()).isTrue();
    }

    @Test
    void graceEndingLaterThanTheSlaLeavesTheSlaDate() {
        ExceptionSla.Due d = ExceptionSla.decide(NOW, SLA, NOW.plus(Duration.ofDays(10)), false, "ET", "Africa/Addis_Ababa");

        assertThat(d.dueAt()).isEqualTo(SLA.due());
        assertThat(d.immediate()).isFalse();
    }

    @Test
    void g4UnknownGraceIsNeverZeroGraceTheSlaAppliesAndTheGapIsKept() {
        ExceptionSla.Due d = ExceptionSla.decide(NOW, SLA, null, true, "ET", "Africa/Addis_Ababa");

        assertThat(d.dueAt()).isEqualTo(SLA.due());
        assertThat(d.immediate()).isFalse();
        assertThat(d.graceUnknown()).isTrue();
        assertThat(d.graceUntil()).isNull();
    }

    @Test
    void anIncompleteCalendarIsCarriedIntoTheDueDate() {
        assertThat(ExceptionSla.decide(NOW, new SlaDate(SLA.due(), true), null, false, "US", "America/Chicago").calendarIncomplete()).isTrue();
    }
}
