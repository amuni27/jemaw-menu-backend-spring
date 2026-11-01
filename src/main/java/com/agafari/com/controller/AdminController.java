package com.agafari.com.controller;

import com.agafari.com.dto.response.AdminAuditPage;
import com.agafari.com.dto.response.AdminSummary;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.repository.AdminAuditEventRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.AdminAuthService;
import com.agafari.com.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF E4-M1: Super Admin APIs. {@code SecurityConfig} requires the database-backed ADMIN role for {@code /api/admin/**};
 * nothing here changes or deletes audit events.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final AdminAuditEventRepository auditEvents;

    @GetMapping("/me")
    public ResponseEntity<AdminSummary> me() {
        return ResponseEntity.ok(AdminAuthService.summary(
                users.findById(currentUser.userId()).orElseThrow(() -> new NotFoundException("User not found"))));
    }

    @GetMapping("/audit-events")
    public ResponseEntity<AdminAuditPage> auditEvents(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        var result = auditEvents.findAllByOrderByOccurredAtDesc(PageRequest.of(page, size));
        var items = result.getContent().stream().map(e -> new AdminAuditPage.Item(e.getId(), e.getOccurredAt(), e.getActorLabel(),
                e.getAction(), e.getTargetType(), e.getTargetId(), e.getOutcome(), e.getDetail())).toList();
        return ResponseEntity.ok(new AdminAuditPage(items, page, size, result.getTotalElements()));
    }
}
