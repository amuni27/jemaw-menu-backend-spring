package com.agafari.com.security;

import com.agafari.com.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public AppUserDetails me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || auth.getPrincipal() == null) {
            throw new ForbiddenException("Unauthorized");
        }

        Object principal = auth.getPrincipal();

        if (principal instanceof AppUserDetails user) {
            return user;
        }

        throw new ForbiddenException("Unauthorized");
    }

    public String businessId() {
        String businessId = me().getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            throw new ForbiddenException("Business not found");
        }
        return businessId;
    }

    /**
     * Ownership check for business-ID path endpoints (AGF-S1-06): authenticated but not yours is 403.
     * Compares with the business on the principal, so no extra query and no hint whether the ID exists.
     */
    public String requireOwnBusiness(String businessId) {
        String own = businessId();
        if (!own.equals(businessId)) {
            throw new ForbiddenException("Not allowed");
        }
        return own;
    }

    public String userId() {
        return me().getId();
    }

    public String email() {
        return me().getEmail();
    }
}

