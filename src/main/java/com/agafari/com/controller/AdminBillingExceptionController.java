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

    /** AGF EX-F: the list (UNRESOLVED by default), newest deadlines first. */
    @GetMapping
    public ResponseEntity<com.agafari.com.dto.response.BillingExceptionPage> list(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "UNRESOLVED") String state,
                                                                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                                                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(exceptions.list(state, page, size));
    }

    /** AGF EX-F (F-2): the badge counts. */
    @GetMapping("/summary")
    public ResponseEntity<com.agafari.com.dto.response.BillingExceptionSummary> summary() {
        return ResponseEntity.ok(exceptions.summary());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<java.util.List<com.agafari.com.dto.response.AdminAuditPage.Item>> history(@PathVariable String id) {
        return ResponseEntity.ok(exceptions.history(id));
    }

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
