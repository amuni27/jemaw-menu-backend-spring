package com.agafari.com.dto.response;

import com.agafari.com.jpa.entity.Business;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BusinessResponse {

    private String id;

    private String name;
    private String businessPhone;

    private String streetAddress;
    private String city;
    private String state;
    private String zipcode;

    private String customSubdomain;
    private boolean open24_7;

    private String currency;

    // ✅ public URL for logo (frontend uses this)
    // DB will store objectKey in a separate column (logoKey), and service maps it here.
    private String logoUrl;

    private Instant createdAt;
    private Instant updatedAt;

    public static BusinessResponse from(Business b, String publicLogoUrl) {
        return BusinessResponse.builder()
                .id(b.getId())
                .name(b.getName())
                .businessPhone(b.getBusinessPhone())
                .streetAddress(b.getStreetAddress())
                .city(b.getCity())
                .state(b.getState())
                .zipcode(b.getZipcode())
                .customSubdomain(b.getCustomSubdomain())
                .open24_7(b.isOpen24_7())
                .currency(b.getCurrency())
                .logoUrl(publicLogoUrl)
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
