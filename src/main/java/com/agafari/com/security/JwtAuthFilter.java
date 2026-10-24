package com.agafari.com.security;


import com.agafari.com.exception.NotFoundException;
import com.agafari.com.service.JwtService;
import com.agafari.com.service.UserDetailsService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final SecurityErrorResponses errorResponses;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip JWT check for non-admin routes + login + swagger
        return path.startsWith("/api/auth/register")
                || path.startsWith("/api/auth/login")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/actuator")
                || path.startsWith("/venue");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");


        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No token -> let Spring Security decide (401 from the entry point on protected paths)
            filterChain.doFilter(request, response);
            return;
        }

        // Expected token failures leave the request anonymous; the entry point then answers 401 (AGF-S1-07).
        // They are logged at WARN by reason only: never the token, and no stack trace.
        String email;
        try {
            email = jwtService.extractSubject(authHeader.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("event=auth.token_rejected reason={}", e.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // Only set auth if not already set
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails user;
            try {
                user = userDetailsService.loadUserByUsername(email);
            } catch (NotFoundException | UsernameNotFoundException e) {
                // Signed token whose subject no longer matches a user (deleted account or changed email)
                log.warn("event=auth.token_rejected reason=unknown_subject");
                filterChain.doFilter(request, response);
                return;
            }

            if (!user.isEnabled()) {
                log.warn("event=auth.account_inactive path={}", request.getRequestURI());
                errorResponses.accountInactive(response);
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    user, null, user.getAuthorities()
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}


