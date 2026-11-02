package com.agafari.com.controller;

import com.agafari.com.dto.request.AdActionRequest;
import com.agafari.com.dto.request.AdImageRequest;
import com.agafari.com.dto.request.AdRequest;
import com.agafari.com.dto.response.AdImpressionsResponse;
import com.agafari.com.dto.response.AdResponse;
import com.agafari.com.dto.response.PresignLogoResponse;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.AgafariAdService;
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

import java.util.List;

/** AGF E4-M7: the Agafari ad inventory ({@code /api/admin/**}: database-backed ADMIN role). No delete endpoint: ads are archived. */
@RestController
@RequestMapping("/api/admin/ads")
@RequiredArgsConstructor
public class AdminAdController {

    private final CurrentUser currentUser;
    private final AgafariAdService ads;

    @GetMapping
    public ResponseEntity<List<AdResponse>> list() {
        return ResponseEntity.ok(ads.list());
    }

    @PostMapping
    public ResponseEntity<AdResponse> create(@RequestBody AdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ads.create(request, currentUser.userId(), currentUser.email()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdResponse> update(@PathVariable String id, @RequestBody AdRequest request) {
        return ResponseEntity.ok(ads.update(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<AdResponse> pause(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.pause(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<AdResponse> resume(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.resume(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<AdResponse> archive(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.archive(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/image/presign")
    public ResponseEntity<PresignLogoResponse> presignImage(@PathVariable String id, @RequestBody AdImageRequest request) {
        return ResponseEntity.ok(ads.presignImage(id, request));
    }

    @PostMapping("/{id}/image/confirm")
    public ResponseEntity<AdResponse> confirmImage(@PathVariable String id, @RequestBody AdImageRequest request) {
        return ResponseEntity.ok(ads.confirmImage(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/image/remove")
    public ResponseEntity<AdResponse> removeImage(@PathVariable String id, @RequestBody AdActionRequest request) {
        return ResponseEntity.ok(ads.removeImage(id, request, currentUser.userId(), currentUser.email()));
    }

    @GetMapping("/{id}/impressions")
    public ResponseEntity<AdImpressionsResponse> impressions(@PathVariable String id) {
        return ResponseEntity.ok(ads.impressions(id));
    }
}
