package com.agafari.com.dto.request;

import com.agafari.com.validation.CountryLocationRules;
import com.agafari.com.validation.GoogleMapsUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.agafari.com.validation.StrongPassword;
import lombok.Data;

import java.util.List;

@Data
@CountryLocationRules // AGF-S2-12 CR1: the location fields depend on the country
public class AuthRegisterRequest {

    // user
    @NotBlank
    private String fullName;

    @NotBlank @Email
    private String email;

    // optional (Node: "" => undefined)
    private String phoneNumber;

    @NotBlank @StrongPassword // AGF-S1-17
    private String password;

    // business
    @NotBlank
    private String businessName;

    @NotBlank @Size(min = 6)
    private String businessPhone;

    /** AGF-S2-12 CR1: ET or US (D-CR1-1); missing = ET */
    private String country;

    // AGF-S2-12 CR1: required for the United States, not collected for Ethiopia (CountryRules)
    private String streetAddress;

    private String city;

    private String state;

    private String zipcode;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Invalid subdomain")
    @Size(min = 3, max = 30)
    private String customSubdomain;

    private boolean open24_7;

    /** AGF-S2-12: Google Maps share link; required for Ethiopia, optional for the United States (CR1) */
    @GoogleMapsUrl
    private String locationUrl;

    @Valid
    private List<BusinessHourDto> businessHours; // optional, validated in service if open24_7=false
}
