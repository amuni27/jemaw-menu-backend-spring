package com.agafari.com.controller;

import com.agafari.com.dto.request.AdActionRequest;
import com.agafari.com.dto.request.AdImageRequest;
import com.agafari.com.dto.request.RestaurantAdRequest;
import com.agafari.com.dto.response.PresignLogoResponse;
import com.agafari.com.dto.response.RestaurantAdResponse;
import com.agafari.com.dto.response.RestaurantAdsView;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.RestaurantAdService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF E4-M8: the owner's own restaurant ads ({@code SecurityConfig}: {@code /api/restaurant-ads/**} needs ROLE_VENUE). The
 * restaurant always comes from the token, so an owner can't address another restaurant. No delete endpoint: ads are archived.
 */
@RestController
@RequestMapping("/api/restaurant-ads")
@RequiredArgsConstructor
public class RestaurantAdController {

    private final CurrentUser currentUser;
    private final RestaurantAdService ads;

    @GetMapping
    public ResponseEntity<RestaurantAdsView> list() {
        return ResponseEntity.ok(ads.view(currentUser.businessId()));
    }

    @PostMapping
    public ResponseEntity<RestaurantAdResponse> create(@RequestBody RestaurantAdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ads.create(currentUser.businessId(), request, currentUser.userId(), currentUser.email()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RestaurantAdResponse> update(@PathVariable String id, @RequestBody RestaurantAdRequest request) {
        return ResponseEntity.ok(ads.update(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<RestaurantAdResponse> pause(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.pause(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<RestaurantAdResponse> resume(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.resume(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<RestaurantAdResponse> archive(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.archive(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/image/presign")
    public ResponseEntity<PresignLogoResponse> presignImage(@PathVariable String id, @RequestBody AdImageRequest request) {
        return ResponseEntity.ok(ads.presignImage(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/image/confirm")
    public ResponseEntity<RestaurantAdResponse> confirmImage(@PathVariable String id, @RequestBody AdImageRequest request) {
        return ResponseEntity.ok(ads.confirmImage(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/image/remove")
    public ResponseEntity<RestaurantAdResponse> removeImage(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.removeImage(currentUser.businessId(), id, request, currentUser.userId(), currentUser.email()));
    }
}
