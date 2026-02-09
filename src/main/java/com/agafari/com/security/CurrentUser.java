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

    public String userId() {
        return me().getId();
    }

    public String email() {
        return me().getEmail();
    }
}

