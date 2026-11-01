package com.agafari.com.controller;

import com.agafari.com.dto.request.PlanRequestDeclineRequest;
import com.agafari.com.dto.request.PlanRequestFulfilRequest;
import com.agafari.com.dto.response.PlanRequestPage;
import com.agafari.com.dto.response.PlanRequestResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.jpa.entity.PlanRequest;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.PlanRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** AGF E4-M4: plan requests for the Super Admin ({@code /api/admin/**}: database-backed ADMIN role). */
@RestController
@RequestMapping("/api/admin/plan-requests")
@RequiredArgsConstructor
public class AdminPlanRequestController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of(PlanRequest.PENDING, PlanRequest.FULFILLED, PlanRequest.DECLINED, PlanRequest.WITHDRAWN);

    private final CurrentUser currentUser;
    private final PlanRequestService service;

    @GetMapping
    public ResponseEntity<PlanRequestPage> list(@RequestParam(defaultValue = "PENDING") String status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || !STATUSES.contains(status)) {
            throw new BadRequestException("status must be a plan request status, page 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        return ResponseEntity.ok(service.list(status, page, size));
    }

    @PostMapping("/{id}/fulfil")
    public ResponseEntity<PlanRequestResponse> fulfil(@PathVariable String id, @RequestBody PlanRequestFulfilRequest request) {
        return ResponseEntity.ok(service.fulfil(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<PlanRequestResponse> decline(@PathVariable String id, @RequestBody PlanRequestDeclineRequest request) {
        return ResponseEntity.ok(service.decline(id, request, currentUser.userId(), currentUser.email()));
    }
}
