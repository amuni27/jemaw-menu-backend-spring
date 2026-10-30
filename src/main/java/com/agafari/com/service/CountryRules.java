package com.agafari.com.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AGF-S2-12 CR1: country-specific location rules (D-CR1-1…6). Ethiopia: the Google Maps link is required and street,
 * city, region and postal code aren't collected. United States: street, city, state (2-letter code) and ZIP are
 * required, the link is optional. The business phone must belong to the country. Only ET and US are supported;
 * a missing country means Ethiopia (every registration before CR1 was Ethiopian).
 */
public final class CountryRules {

    public static final String ETHIOPIA = "ET";
    public static final String UNITED_STATES = "US";

    public static final String COUNTRY_MESSAGE = "Choose Ethiopia or United States.";
    public static final String LINK_REQUIRED_MESSAGE = "Add your restaurant's Google Maps link.";
    public static final String LINK_KEEP_MESSAGE = "Restaurants in Ethiopia need a Google Maps link. Replace it instead of removing it.";
    public static final String STREET_MESSAGE = "Street address is required.";
    public static final String CITY_MESSAGE = "City is required.";
    public static final String STATE_MESSAGE = "Choose a US state.";
    public static final String ZIP_MESSAGE = "Enter a ZIP code like 12345 or 12345-6789.";
    public static final String ET_PHONE_MESSAGE = "Enter an Ethiopian phone number (+251…).";
    public static final String US_PHONE_MESSAGE = "Enter a US phone number (+1…).";

    /** The 50 states and the District of Columbia (D-CR1-6). */
    public static final Set<String> US_STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY",
            "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH",
            "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY");

    private static final Pattern ZIP = Pattern.compile("\\d{5}(-\\d{4})?");

    private CountryRules() {
    }

    /** ET or US; blank means ET; anything else is null (unsupported). */
    public static String country(String raw) {
        if (raw == null || raw.isBlank()) return ETHIOPIA;
        String code = raw.strip().toUpperCase(Locale.ROOT);
        return ETHIOPIA.equals(code) || UNITED_STATES.equals(code) ? code : null;
    }

    public static boolean isUnitedStates(String country) {
        return UNITED_STATES.equals(country);
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** International format: ET +251 and 9 digits, US +1 and 10 digits; spaces and dashes are allowed. */
    public static boolean phoneMatches(String country, String phone) {
        if (phone == null) return false;
        String compact = phone.replaceAll("[\\s\\-()]", "");
        return isUnitedStates(country) ? compact.matches("\\+1\\d{10}") : compact.matches("\\+251\\d{9}");
    }

    public static String phoneMessage(String country) {
        return isUnitedStates(country) ? US_PHONE_MESSAGE : ET_PHONE_MESSAGE;
    }

    public static String usState(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    public static boolean isUsState(String value) {
        return value != null && US_STATES.contains(usState(value));
    }

    public static boolean isZip(String value) {
        return value != null && ZIP.matcher(value.strip()).matches();
    }

    /** Field errors for a registration (field name → message), in form order; empty when valid. */
    public static Map<String, String> registrationErrors(String rawCountry, String businessPhone, String streetAddress,
                                                         String city, String state, String zipcode, String locationUrl) {
        Map<String, String> errors = new LinkedHashMap<>();
        String country = country(rawCountry);
        if (country == null) {
            errors.put("country", COUNTRY_MESSAGE);
            return errors;
        }
        if (!isBlank(businessPhone) && !phoneMatches(country, businessPhone)) errors.put("businessPhone", phoneMessage(country));
        if (isUnitedStates(country)) {
            if (isBlank(streetAddress)) errors.put("streetAddress", STREET_MESSAGE);
            if (isBlank(city)) errors.put("city", CITY_MESSAGE);
            if (!isUsState(state)) errors.put("state", STATE_MESSAGE);
            if (!isZip(zipcode)) errors.put("zipcode", ZIP_MESSAGE);
        } else if (isBlank(locationUrl)) {
            errors.put("locationUrl", LINK_REQUIRED_MESSAGE);
        }
        return errors;
    }
}
