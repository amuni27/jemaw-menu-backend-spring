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
        /** AGF-S2-22 */
        private EmailVerification emailVerification;
    }

    /**
     * AGF-S2-22: the owner's verification state. {@code available}: links can be delivered now; {@code publishGateEnabled}:
     * publishing currently requires a verified email (flag on AND available).
     */
    public record EmailVerification(boolean verified, boolean available, boolean publishGateEnabled) {
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
        /** AGF-S2-02 */
        private String currency;
        /** AGF-S2-01 */
        private String logoUrl;
        /** AGF-S2-12 */
        private String locationUrl;
        /** AGF-S2-12 CR1 */
        private String country;
        /** AGF-S2-26 */
        private String timeZone;
    }
}
