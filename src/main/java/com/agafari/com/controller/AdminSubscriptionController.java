package com.agafari.com.controller;

import com.agafari.com.dto.request.SubscriptionCreateRequest;
import com.agafari.com.dto.request.SubscriptionUpdateRequest;
import com.agafari.com.dto.response.AdminBusinessPage;
import com.agafari.com.dto.response.BusinessSubscriptionView;
import com.agafari.com.dto.response.SubscriptionPlanResponse;
import com.agafari.com.dto.response.SubscriptionResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AGF E4-M3: plans, a read-only restaurant list and manual subscription records, Super Admin only ({@code SecurityConfig}:
 * {@code /api/admin/**} needs the database-backed ADMIN role). There is no owner or public endpoint.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentUser currentUser;
    private final SubscriptionService service;

    @GetMapping("/plans")
    public ResponseEntity<List<SubscriptionPlanResponse>> plans() {
        return ResponseEntity.ok(service.plans());
    }

    @GetMapping("/businesses")
    public ResponseEntity<AdminBusinessPage> businesses(@RequestParam(required = false) String query,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        return ResponseEntity.ok(service.businesses(query, page, size));
    }

    @GetMapping("/businesses/{businessId}/subscription")
    public ResponseEntity<BusinessSubscriptionView> subscription(@PathVariable String businessId) {
        return ResponseEntity.ok(service.view(businessId));
    }

    @GetMapping("/businesses/{businessId}/subscriptions")
    public ResponseEntity<List<SubscriptionResponse>> history(@PathVariable String businessId) {
        return ResponseEntity.ok(service.history(businessId));
    }

    @PostMapping("/businesses/{businessId}/subscription")
    public ResponseEntity<SubscriptionResponse> create(@PathVariable String businessId, @RequestBody SubscriptionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(businessId, request, currentUser.userId(), currentUser.email()));
    }

    @PatchMapping("/businesses/{businessId}/subscription")
    public ResponseEntity<SubscriptionResponse> update(@PathVariable String businessId, @RequestBody SubscriptionUpdateRequest request) {
        return ResponseEntity.ok(service.update(businessId, request, currentUser.userId(), currentUser.email()));
    }
}
