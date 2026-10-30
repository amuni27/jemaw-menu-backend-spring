package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BusinessPublicResponse {
    private String id;
    private String name;
    private String businessPhone;
    private String streetAddress;
    private String city;
    private String state;
    private String zipcode;
    private String customSubdomain;
    private boolean open24_7;
    /** AGF-S2-02 */
    private String currency;
    /** AGF-S2-01: public logo URL, or null (the guest menu then shows the initials) */
    private String logoUrl;

    /** AGF-S2-12: the stored, validated Google Maps link ("Get Directions"); null = none */
    private String locationUrl;
    /** AGF-S2-03: weekly opening hours, Monday first; empty when none are stored */
    private List<PublicBusinessHoursResponse> hours;
    /** AGF-S2-03: time zone of the hours (D3: Africa/Addis_Ababa for every business for now) */
    private String timeZone;
}
