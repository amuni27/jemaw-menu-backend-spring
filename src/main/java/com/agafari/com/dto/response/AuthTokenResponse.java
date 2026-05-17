package com.agafari.com.dto.response;

import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AuthTokenResponse {
    private String token;
    private UserSummary user;
    private BusinessSummary business;

    @Data @AllArgsConstructor
    public static class UserSummary {
        private String id;
        private String fullName;
        private String phoneNumber;
        private String email;
        private String role;
    }

    @Data @AllArgsConstructor
    public static class BusinessSummary {
        private String id;
        private String name;
        private String customSubdomain;
        private String businessPhone;
        private String streetAddress;
        private String city;
        private String state;
        private String zipcode;
        private boolean open24_7;
        private List<BusinessHoursResponse> businessHours;
    }
}
