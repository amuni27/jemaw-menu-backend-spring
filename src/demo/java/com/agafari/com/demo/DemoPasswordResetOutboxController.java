package com.agafari.com.demo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Local demo only (AGF-S2-13A): hands the latest reset link for an email to a caller on this machine, in place of an
 * inbox. Answers only loopback requests; each link can be read once. Not part of the production artifact (src/demo).
 */
@RestController
@Profile("demo")
@RequiredArgsConstructor
public class DemoPasswordResetOutboxController {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final DemoPasswordResetOutbox outbox;

    @GetMapping("/api/demo/password-reset-outbox")
    public ResponseEntity<Map<String, String>> latest(@RequestParam String email, HttpServletRequest request) {
        if (!LOOPBACK.contains(request.getRemoteAddr())) return ResponseEntity.notFound().build();
        return outbox.take(email)
                .map(url -> ResponseEntity.ok(Map.of("resetUrl", url)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
