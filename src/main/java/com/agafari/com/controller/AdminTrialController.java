package com.agafari.com.controller;

import com.agafari.com.dto.request.TrialConvertRequest;
import com.agafari.com.dto.request.TrialStartRequest;
import com.agafari.com.dto.response.SubscriptionResponse;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.TrialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF E4-M5: manual trial start and trial-to-manual conversion ({@code /api/admin/**}: database-backed ADMIN role). Extend
 * and end use the E4-M3 subscription PATCH.
 */
@RestController
@RequestMapping("/api/admin/businesses/{businessId}/trial")
@RequiredArgsConstructor
public class AdminTrialController {

    private final CurrentUser currentUser;
    private final TrialService trials;

    @PostMapping
    public ResponseEntity<SubscriptionResponse> start(@PathVariable String businessId, @RequestBody TrialStartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trials.start(businessId, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/convert")
    public ResponseEntity<SubscriptionResponse> convert(@PathVariable String businessId, @RequestBody TrialConvertRequest request) {
        return ResponseEntity.ok(trials.convert(businessId, request, currentUser.userId(), currentUser.email()));
    }
}
