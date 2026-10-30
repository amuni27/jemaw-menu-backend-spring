package com.agafari.com.timezone;

import com.agafari.com.service.CountryRules;
import com.agafari.com.service.TimeZoneRules;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** AGF-S2-26: allowed zones per country (D-S2-26-1, -3) and the state defaults (D-S2-26-4). */
class TimeZoneRulesTest {

    @Test
    void offersSevenValidUsZones() {
        assertThat(TimeZoneRules.US_ZONES).containsExactly("America/New_York", "America/Chicago", "America/Denver",
                "America/Phoenix", "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu");
        TimeZoneRules.US_ZONES.forEach(ZoneId::of); // every ID is a real IANA zone
    }

    @Test
    void everyStateHasAUsDefault() {
        for (String state : CountryRules.US_STATES) {
            assertThat(TimeZoneRules.US_ZONES).as(state).contains(TimeZoneRules.defaultForState(state));
        }
        assertThat(TimeZoneRules.defaultForState("ny")).isEqualTo("America/New_York");
        assertThat(TimeZoneRules.defaultForState("TX")).isEqualTo("America/Chicago");
        assertThat(TimeZoneRules.defaultForState("CO")).isEqualTo("America/Denver");
        assertThat(TimeZoneRules.defaultForState("AZ")).isEqualTo("America/Phoenix");
        assertThat(TimeZoneRules.defaultForState("CA")).isEqualTo("America/Los_Angeles");
        assertThat(TimeZoneRules.defaultForState("AK")).isEqualTo("America/Anchorage");
        assertThat(TimeZoneRules.defaultForState("HI")).isEqualTo("Pacific/Honolulu");
        assertThat(TimeZoneRules.defaultForState("Oromia")).isNull();
    }

    @Test
    void ethiopiaIsAlwaysAddisAbaba() {
        assertThat(TimeZoneRules.zoneFor("ET", "America/New_York")).isEqualTo("Africa/Addis_Ababa");
        assertThat(TimeZoneRules.zoneFor("ET", null)).isEqualTo("Africa/Addis_Ababa");
        assertThat(TimeZoneRules.zoneFor("US", " America/Denver ")).isEqualTo("America/Denver");
    }

    @Test
    void onlyTheSevenZonesAreUsZones() {
        assertThat(TimeZoneRules.isUsZone("America/Phoenix")).isTrue();
        for (String other : new String[]{null, "", "Africa/Addis_Ababa", "Europe/London", "America/Toronto", "EST", "garbage"}) {
            assertThat(TimeZoneRules.isUsZone(other)).as(String.valueOf(other)).isFalse();
        }
    }

    @Test
    void anUnreadableStoredZoneFallsBackToAddisAbaba() {
        assertThat(TimeZoneRules.zoneId("America/Chicago")).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(TimeZoneRules.zoneId(null)).isEqualTo(ZoneId.of("Africa/Addis_Ababa"));
        assertThat(TimeZoneRules.zoneId("Not/AZone")).isEqualTo(ZoneId.of("Africa/Addis_Ababa"));
    }
}
