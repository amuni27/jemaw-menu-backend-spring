package com.agafari.com.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AGF-S2-26: the restaurant's time zone (D-S2-26-1…5). Ethiopia is always Africa/Addis_Ababa; US restaurants choose
 * one of seven US zones, pre-selected from the state (states split across zones can pick another one).
 */
public final class TimeZoneRules {

    public static final String ADDIS_ABABA = "Africa/Addis_Ababa";
    public static final String US_ZONE_MESSAGE = "Choose a US time zone.";

    public static final List<String> US_ZONES = List.of("America/New_York", "America/Chicago", "America/Denver",
            "America/Phoenix", "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu");

    /** Each state's main zone (D-S2-26-4). */
    private static final Map<String, String> STATE_DEFAULTS = Stream.of(
            zone("America/New_York", "CT", "DE", "DC", "FL", "GA", "IN", "KY", "ME", "MD", "MA", "MI", "NH", "NJ", "NY",
                    "NC", "OH", "PA", "RI", "SC", "VT", "VA", "WV"),
            zone("America/Chicago", "AL", "AR", "IL", "IA", "KS", "LA", "MN", "MS", "MO", "NE", "ND", "OK", "SD", "TN",
                    "TX", "WI"),
            zone("America/Denver", "CO", "ID", "MT", "NM", "UT", "WY"),
            zone("America/Phoenix", "AZ"),
            zone("America/Los_Angeles", "CA", "NV", "OR", "WA"),
            zone("America/Anchorage", "AK"),
            zone("Pacific/Honolulu", "HI")
    ).flatMap(m -> m.entrySet().stream()).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private TimeZoneRules() {
    }

    private static Map<String, String> zone(String zone, String... states) {
        return Stream.of(states).collect(Collectors.toMap(s -> s, s -> zone));
    }

    public static boolean isUsZone(String value) {
        return value != null && US_ZONES.contains(value.strip());
    }

    /** The state's main zone, or null for an unknown state. */
    public static String defaultForState(String state) {
        return state == null ? null : STATE_DEFAULTS.get(state.strip().toUpperCase(Locale.ROOT));
    }

    /** The zone to store: Ethiopia is always Addis Ababa; the US value is already validated. */
    public static String zoneFor(String country, String requested) {
        return CountryRules.isUnitedStates(country) && requested != null ? requested.strip() : ADDIS_ABABA;
    }

    /** The stored zone as a ZoneId; anything unreadable falls back to Addis Ababa (D3 default). */
    public static ZoneId zoneId(String stored) {
        try {
            return stored == null || stored.isBlank() ? ZoneId.of(ADDIS_ABABA) : ZoneId.of(stored.strip());
        } catch (DateTimeException e) {
            return ZoneId.of(ADDIS_ABABA);
        }
    }
}
