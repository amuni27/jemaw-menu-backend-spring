package com.agafari.com.controller;

import com.agafari.com.dto.request.ScheduledDowngradeRequest;
import com.agafari.com.dto.response.ScheduledChangeResponse;
import com.agafari.com.exception.ForbiddenException;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.ScheduledChangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AGF SUB-R2-11 (TA §7): an owner's scheduled changes. Behind {@code billing-enabled} (default off). */
@RestController
@RequestMapping("/api/billing/subscription")
@RequiredArgsConstructor
public class BillingSubscriptionController {

    private final CurrentUser currentUser;
    private final ScheduledChangeService service;

    private void requireBilling() {
        if (!service.isBillingEnabled()) throw new ForbiddenException("Billing isn't available yet");
    }

    @PostMapping("/change")
    public ResponseEntity<ScheduledChangeResponse> change(@RequestBody ScheduledDowngradeRequest request) {
        requireBilling();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.scheduleDowngrade(currentUser.businessId(), request.planCode(), currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<ScheduledChangeResponse> cancel() {
        requireBilling();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.scheduleCancel(currentUser.businessId(), currentUser.userId(), currentUser.email()));
    }

    @DeleteMapping("/scheduled-change")
    public ResponseEntity<Void> resume() {
        requireBilling();
        service.cancelPending(currentUser.businessId(), currentUser.userId(), currentUser.email());
        return ResponseEntity.noContent().build();
    }
}
