package com.agafari.com.controller;

import com.agafari.com.dto.request.PlanPriceCreateRequest;
import com.agafari.com.dto.response.PlanPriceResponse;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.PlanPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AGF SUB-R3-01: Super Admin price versions, audited. Nothing here shows a price to an owner. */
@RestController
@RequestMapping("/api/admin/prices")
@RequiredArgsConstructor
public class AdminPlanPriceController {

    private final CurrentUser currentUser;
    private final PlanPriceService service;

    @GetMapping
    public ResponseEntity<List<PlanPriceResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<PlanPriceResponse> create(@RequestBody PlanPriceCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createVersion(request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<PlanPriceResponse> activate(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.activate(id, currentUser.userId(), currentUser.email()));
    }
}
