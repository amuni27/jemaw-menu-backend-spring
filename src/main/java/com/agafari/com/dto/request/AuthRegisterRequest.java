package com.agafari.com.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class AuthRegisterRequest {

    // user
    @NotBlank
    private String fullName;

    @NotBlank @Email
    private String email;

    // optional (Node: "" => undefined)
    private String phoneNumber;

    @NotBlank @Size(min = 6)
    private String password;

    // business
    @NotBlank
    private String businessName;

    @NotBlank @Size(min = 6)
    private String businessPhone;

    @NotBlank
    private String streetAddress;

    @NotBlank
    private String city;

    @NotBlank @Size(min = 2, max = 60)
    private String state;

    // optional in Ethiopia (Node stores "" when not provided)
    private String zipcode;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Invalid subdomain")
    @Size(min = 3, max = 30)
    private String customSubdomain;

    private boolean open24_7;

    @Valid
    private List<BusinessHourDto> businessHours; // optional, validated in service if open24_7=false
}
