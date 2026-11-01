package com.agafari.com.controller;

import com.agafari.com.dto.request.MonetizationConfigRequest;
import com.agafari.com.dto.response.MonetizationConfigHistoryPage;
import com.agafari.com.dto.response.MonetizationConfigResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.MonetizationConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF E4-M2: the platform monetization configuration, Super Admin only ({@code SecurityConfig}: {@code /api/admin/**}
 * needs the database-backed ADMIN role). Owners and guests can't read it yet (D-M2-3).
 */
@RestController
@RequestMapping("/api/admin/monetization-config")
@RequiredArgsConstructor
public class AdminMonetizationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentUser currentUser;
    private final MonetizationConfigService service;

    @GetMapping
    public ResponseEntity<MonetizationConfigResponse> current() {
        return ResponseEntity.ok(service.current());
    }

    @PutMapping
    public ResponseEntity<MonetizationConfigResponse> change(@RequestBody MonetizationConfigRequest request) {
        return ResponseEntity.ok(service.change(request, currentUser.userId(), currentUser.email()));
    }

    @GetMapping("/history")
    public ResponseEntity<MonetizationConfigHistoryPage> history(@RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        return ResponseEntity.ok(service.history(page, size));
    }
}
