package com.agafari.com.controller;

import com.agafari.com.billing.ExceptionService;
import com.agafari.com.dto.request.BillingExceptionActionRequest;
import com.agafari.com.dto.response.BillingExceptionReplayResponse;
import com.agafari.com.dto.response.BillingExceptionView;
import com.agafari.com.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF SUB-R4-11 (TA §7, DD §8.4): the Super Admin's actions on one financial exception ({@code /api/admin/**}: ADMIN only).
 * Every action needs the current {@code rowVersion} and is audited. The queue and other read APIs are SUB-R8-01.
 */
@RestController
@RequestMapping("/api/admin/billing/exceptions")
@RequiredArgsConstructor
public class AdminBillingExceptionController {

    private final CurrentUser currentUser;
    private final ExceptionService exceptions;

    @GetMapping("/{id}")
    public ResponseEntity<BillingExceptionView> get(@PathVariable String id) {
        return ResponseEntity.ok(exceptions.get(id));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<BillingExceptionView> claim(@PathVariable String id, @RequestBody BillingExceptionActionRequest request) {
        return ResponseEntity.ok(exceptions.claim(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<BillingExceptionView> note(@PathVariable String id, @RequestBody BillingExceptionActionRequest request) {
        return ResponseEntity.ok(exceptions.addNote(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/escalate")
    public ResponseEntity<BillingExceptionView> escalate(@PathVariable String id, @RequestBody BillingExceptionActionRequest request) {
        return ResponseEntity.ok(exceptions.escalate(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<BillingExceptionView> resolve(@PathVariable String id, @RequestBody BillingExceptionActionRequest request) {
        return ResponseEntity.ok(exceptions.resolve(id, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<BillingExceptionReplayResponse> replay(@PathVariable String id, @RequestBody BillingExceptionActionRequest request) {
        return ResponseEntity.ok(exceptions.replay(id, request, currentUser.userId(), currentUser.email()));
    }
}
