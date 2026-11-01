package com.agafari.com.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final SecurityErrorResponses errorResponses;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, SecurityErrorResponses errorResponses) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.errorResponses = errorResponses;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // swagger
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/forgot-password", // AGF-S2-13A
                                "/api/auth/reset-password",
                                "/api/auth/verify-email", // AGF-S2-22 (resend stays signed-in only)
                                "/api/admin/auth/login", // AGF E4-M1 (every other /api/admin/** path needs ROLE_ADMIN)
                                "/api/demo/**", // only exists in -Pdemo builds (local demo outbox)
                                "/actuator/**",
                                "/venue/**",
                                "/venue/items/**"
                        ).permitAll()

                        // owner APIs: restaurant owners only (AGF-S1-06); tenant ownership is checked in the services
                        .requestMatchers(
                                "/api/menus/**",
                                "/api/categories/**",
                                "/api/items/**",
                                "/api/business/**",
                                "/api/user/**",
                                "/api/specials/**", // AGF E4-M1: now refused by role, not only by the services
                                "/api/category-specials/**",
                                "/api/plan/**" // AGF E4-M4: owner plan view and requests (own restaurant from the token)
                        ).hasRole("VENUE")

                        // AGF E4-M1: Super Admin APIs, database-backed ADMIN role on every request
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )
                // IMPORTANT: add JWT filter into Spring Security chain
                // JSON 401 for missing/invalid tokens and 403 for forbidden roles (AGF-S1-07)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorResponses.authenticationEntryPoint())
                        .accessDeniedHandler(errorResponses.accessDeniedHandler()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(
                "http://localhost:5175",
                "http://127.0.0.1:5175",
                "https://agafari-menu.onrender.com"
        ));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));

        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }


    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
