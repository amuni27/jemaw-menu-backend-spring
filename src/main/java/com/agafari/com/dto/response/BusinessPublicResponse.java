package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

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
}
