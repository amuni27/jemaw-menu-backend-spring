package com.agafari.com.billing.webhook;

import com.agafari.com.billing.BillingStrategyRegistry;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.SignatureVerifier;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.security.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGF SUB-R4-07 (DD §8.1): verify, store, acknowledge. No business logic runs in the request: the {@code inbox.process} job
 * does that later. Public by design: the signature is the authentication. A 404 while billing is off, and for an unknown
 * provider, so the route reveals nothing. The body is never logged.
 */
@Slf4j
@RestController
public class WebhookController {

    private final BillingStrategyRegistry registry;
    private final InboxService inbox;
    private final AuthRateLimiter rateLimiter;
    private final Set<Market> enabled = ConcurrentHashMap.newKeySet();

    public WebhookController(BillingStrategyRegistry registry, InboxService inbox, AuthRateLimiter rateLimiter, BillingFlags flags) {
        this.registry = registry;
        this.inbox = inbox;
        this.rateLimiter = rateLimiter;
        if (flags.us().billingEnabled()) enabled.add(Market.US);
        if (flags.et().billingEnabled()) enabled.add(Market.ET);
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setBillingEnabled(Market market, boolean on) {
        if (on) enabled.add(market);
        else enabled.remove(market);
    }

    /** AGF SUB-R5-02 (PO D5): the largest webhook body accepted, enforced before signature verification and persistence. */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    @PostMapping("/api/billing/webhooks/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider, @RequestHeader Map<String, String> headers, HttpServletRequest http) throws IOException {
        if (enabled.isEmpty()) throw new NotFoundException("Not found");
        BillingStrategy strategy = registry.forProvider(provider).filter(s -> enabled.contains(s.market())).orElseThrow(() -> new NotFoundException("Not found"));
        rateLimiter.countWebhook(http.getRemoteAddr());
        byte[] body = boundedBody(http);
        if (body == null) {
            log.warn("event=billing.webhook_rejected provider={} reason=too_large limitBytes={}", strategy.provider(), MAX_BODY_BYTES);
            return ResponseEntity.status(413).build();
        }
        Map<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        SignatureVerifier verifier = strategy.client().signatureVerifier();
        if (!verifier.verify(caseInsensitive, body)) throw rejected(strategy.provider(), "signature");
        InboundEvent event;
        try {
            event = verifier.identify(body);
        } catch (IllegalArgumentException e) {
            throw rejected(strategy.provider(), "malformed");
        }
        if (InboxService.isReserved(event)) throw rejected(strategy.provider(), "reserved"); // AGF SUB-R4-14 (G4)
        inbox.store(strategy.provider(), event, body);
        return ResponseEntity.ok().build();
    }

    /**
     * The body, or null when it exceeds {@link #MAX_BODY_BYTES}: by its declared length (the header, or the container's
     * value), or by what is actually streamed, read no further than one byte past the limit. Nothing is buffered beyond that.
     */
    static byte[] boundedBody(HttpServletRequest http) throws IOException {
        long declared = http.getContentLengthLong();
        String header = http.getHeader("Content-Length");
        if (header != null) {
            try {
                declared = Math.max(declared, Long.parseLong(header.strip()));
            } catch (NumberFormatException e) {
                return null; // a malformed length is refused like an oversize one
            }
        }
        if (declared > MAX_BODY_BYTES) return null;
        try (InputStream in = http.getInputStream()) {
            byte[] read = in.readNBytes(MAX_BODY_BYTES + 1);
            return read.length > MAX_BODY_BYTES ? null : read;
        }
    }

    private static BadRequestException rejected(String provider, String reason) {
        log.warn("event=billing.webhook_rejected provider={} reason={}", provider, reason);
        return new BadRequestException("Invalid webhook");
    }
}
