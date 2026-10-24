package com.agafari.com.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Getter
public class AppUserDetails implements UserDetails {

    private final String id;
    private final String email;
    private final String password;
    private final String businessId;
    private final Collection<? extends GrantedAuthority> authorities;
    /** Only ACTIVE accounts may use the API (AGF-S1-07). */
    private final boolean active;

    public AppUserDetails(
            String id,
            String email,
            String password,
            String businessId,
            Collection<? extends GrantedAuthority> authorities,
            boolean active
    ) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.businessId = businessId;
        this.authorities = authorities;
        this.active = active;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return email; }

    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}

