package com.agafari.com.dto.request;

import lombok.Data;

@Data
public class BusinessUpdateRequest {
    private String name;
    private String businessPhone;
    private String streetAddress;
    private String city;
    private String state;
    private String zipcode;
    private String currency;
    private String customSubdomain;
}
