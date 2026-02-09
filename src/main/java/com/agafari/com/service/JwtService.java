package com.agafari.com.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expiresMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiresMinutes}") long expiresMinutes
    ) {
        // HS256 needs at least 32 bytes secret (256-bit)
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalArgumentException("app.jwt.secret must be at least 32 characters for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiresMinutes = expiresMinutes;
    }

    /**
     * Creates JWT similar to Node: { userId, businessId, role } and subject=email.
     */
    public String signToken(String email, String userId, String businessId, String role) {
        Instant now = Instant.now();
        Instant exp = now.plus(expiresMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(email) // keep subject = email, matches your filter
                .claim("userId", userId)
                .claim("businessId", businessId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key) // jjwt picks HS256 automatically for HMAC key
                .compact();
    }

    public String extractSubject(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractUserId(String token) {
        return extractAllClaims(token).get("userId", String.class);
    }

    public String extractBusinessId(String token) {
        return extractAllClaims(token).get("businessId", String.class);
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
