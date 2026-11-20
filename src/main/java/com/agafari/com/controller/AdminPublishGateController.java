package com.agafari.com.controller;

import com.agafari.com.dto.response.PublishDryRunView;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.PublishGateDryRun;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF BE-6 (register §56): the publishing-gate dry run, Super Admin only ({@code /api/admin/**}). Read only, except the sign-off.
 * Nothing here enables the gate: that stays a configuration change checked by the flag matrix at startup.
 */
@RestController
@RequestMapping("/api/admin/publish-gate")
@RequiredArgsConstructor
public class AdminPublishGateController {

    private static final int MAX_PAGE_SIZE = 500;

    private final CurrentUser currentUser;
    private final PublishGateDryRun dryRun;

    @GetMapping("/dry-run")
    public ResponseEntity<PublishDryRunView.Summary> summary(@RequestParam String market) {
        return ResponseEntity.ok(dryRun.report(PublishGateDryRun.market(market)).summary());
    }

    @GetMapping("/dry-run/sections/{section}")
    public ResponseEntity<PublishDryRunView.Page> section(@PathVariable String section, @RequestParam String market,
                                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        PublishDryRunView.Section s;
        try {
            s = PublishDryRunView.Section.valueOf(section.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown section");
        }
        return ResponseEntity.ok(dryRun.page(PublishGateDryRun.market(market), s, page, size));
    }

    @GetMapping(value = "/dry-run.csv", produces = "text/csv")
    public ResponseEntity<String> csv(@RequestParam String market) {
        String m = PublishGateDryRun.market(market).name();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"publish-gate-dry-run-" + m + ".csv\"").body(dryRun.csv(PublishGateDryRun.market(market)));
    }

    @PostMapping("/dry-run/sign-off")
    public ResponseEntity<PublishDryRunView.SignOffResponse> signOff(@RequestBody PublishDryRunView.SignOffRequest request) {
        if (request == null) throw new BadRequestException("market and fingerprint are required");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dryRun.signOff(PublishGateDryRun.market(request.market()), request.fingerprint(), currentUser.userId(), currentUser.email()));
    }
}
