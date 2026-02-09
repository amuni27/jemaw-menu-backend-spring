package com.agafari.com.service;

import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsService implements org.springframework.security.core.userdetails.UserDetailsService {

    private final UserRepository userRepo;

    @Override
    public UserDetails loadUserByUsername(String email) {
        var user = userRepo.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));


        // Example authorities (adjust to your roles)
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        return new AppUserDetails(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getBusiness() != null ? user.getBusiness().getId() : null,
                authorities
        );
    }
}

