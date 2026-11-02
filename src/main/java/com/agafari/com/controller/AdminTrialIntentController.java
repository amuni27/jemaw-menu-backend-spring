package com.agafari.com.controller;

import com.agafari.com.dto.response.TrialIntentOverview;
import com.agafari.com.service.TrialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AGF E4-M5 recovery: trials pending recovery, read-only ({@code /api/admin/**}: database-backed ADMIN role). */
@RestController
@RequestMapping("/api/admin/trial-intents")
@RequiredArgsConstructor
public class AdminTrialIntentController {

    private final TrialService trials;

    @GetMapping
    public ResponseEntity<TrialIntentOverview> pending() {
        return ResponseEntity.ok(trials.intentOverview());
    }
}
