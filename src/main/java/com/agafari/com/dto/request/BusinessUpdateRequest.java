package com.agafari.com.dto.request;

import com.agafari.com.validation.GoogleMapsUrl;
import lombok.Data;

@Data
public class BusinessUpdateRequest {
    private String name;
    private String businessPhone;
    private String streetAddress;
    private String city;
    private String state;
    private String zipcode;
    /** AGF-S2-26: United States only; omitted = unchanged */
    private String timeZone;
    private String currency;
    private String customSubdomain;

    /** AGF-S2-12 (D-S2-12-P1): omitted/null = unchanged, blank = remove, otherwise a validated Google Maps link */
    @GoogleMapsUrl
    private String locationUrl;
}
