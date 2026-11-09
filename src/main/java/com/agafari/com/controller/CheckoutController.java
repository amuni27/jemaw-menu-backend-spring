package com.agafari.com.controller;

import com.agafari.com.billing.CheckoutService;
import com.agafari.com.dto.request.CheckoutCreateRequest;
import com.agafari.com.dto.response.CheckoutResponse;
import com.agafari.com.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF SUB-R4-05: the owner's checkouts ({@code SecurityConfig}: ROLE_VENUE), behind {@code billing-enabled}. The restaurant
 * always comes from the token. Deliberately not transactional: the provider call must run outside any transaction.
 */
@RestController
@RequestMapping("/api/billing/checkouts")
@RequiredArgsConstructor
public class CheckoutController {

    private final CurrentUser currentUser;
    private final CheckoutService checkouts;

    /** 201 a new OPEN checkout, 200 an existing one, 202 still CREATING. */
    @PostMapping
    public ResponseEntity<CheckoutResponse> create(@RequestBody(required = false) CheckoutCreateRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        CheckoutService.Outcome outcome = checkouts.create(currentUser.businessId(), currentUser.userId(), request == null ? null : request.priceId(), idempotencyKey);
        return ResponseEntity.status(outcome.status()).body(outcome.checkout());
    }

    /** For the return page. It only reads: the browser redirect never activates anything. */
    @GetMapping("/{id}")
    public ResponseEntity<CheckoutResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(checkouts.get(currentUser.businessId(), id));
    }
}
