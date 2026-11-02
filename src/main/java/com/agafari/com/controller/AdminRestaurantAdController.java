package com.agafari.com.controller;

import com.agafari.com.dto.request.RestaurantAdModerationRequest;
import com.agafari.com.dto.response.RestaurantAdResponse;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.RestaurantAdAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AGF E4-M8: the Super Admin's oversight of restaurant ads ({@code /api/admin/**}: database-backed ADMIN role). List,
 * suspend and reinstate only: no create, edit, archive or delete.
 */
@RestController
@RequestMapping("/api/admin/restaurant-ads")
@RequiredArgsConstructor
public class AdminRestaurantAdController {

    private final CurrentUser currentUser;
    private final RestaurantAdAdminService ads;

    @GetMapping
    public ResponseEntity<List<RestaurantAdResponse>> list(@RequestParam(required = false) String businessId, @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ads.list(businessId, status));
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<RestaurantAdResponse> suspend(@PathVariable String id, @RequestBody RestaurantAdModerationRequest request) {
        return ResponseEntity.ok(ads.suspend(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/reinstate")
    public ResponseEntity<RestaurantAdResponse> reinstate(@PathVariable String id, @RequestBody RestaurantAdModerationRequest request) {
        return ResponseEntity.ok(ads.reinstate(id, request, currentUser.userId(), currentUser.email()));
    }
}
