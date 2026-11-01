package com.agafari.com.controller;

import com.agafari.com.dto.request.PlanRequestCreateRequest;
import com.agafari.com.dto.request.PlanRequestWithdrawRequest;
import com.agafari.com.dto.response.OwnerPlanView;
import com.agafari.com.dto.response.PlanRequestResponse;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.PlanRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF E4-M4: the owner's plan view and plan requests ({@code SecurityConfig}: {@code /api/plan/**} needs ROLE_VENUE). The
 * restaurant always comes from the token, so an owner can't address another restaurant.
 */
@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
public class OwnerPlanController {

    private final CurrentUser currentUser;
    private final PlanRequestService service;

    @GetMapping
    public ResponseEntity<OwnerPlanView> view() {
        return ResponseEntity.ok(service.ownerView(currentUser.businessId()));
    }

    @PostMapping("/requests")
    public ResponseEntity<PlanRequestResponse> request(@RequestBody PlanRequestCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.request(currentUser.businessId(), request, currentUser.userId(), currentUser.email()));
    }

    @PatchMapping("/requests/current")
    public ResponseEntity<PlanRequestResponse> withdraw(@RequestBody PlanRequestWithdrawRequest request) {
        return ResponseEntity.ok(service.withdraw(currentUser.businessId(), request, currentUser.userId(), currentUser.email()));
    }
}
