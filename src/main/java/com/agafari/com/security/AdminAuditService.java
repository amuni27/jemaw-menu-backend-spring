package com.agafari.com.security;

import com.agafari.com.jpa.entity.AdminAuditEvent;
import com.agafari.com.jpa.repository.AdminAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * AGF E4-M1: records important Super Admin actions in the append-only audit table. Callers pass fixed action names and
 * short reason categories only: never passwords, tokens, secrets or raw emails (actors are a user id and a masked label).
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    public static final String BOOTSTRAP_CREATED = "ADMIN_BOOTSTRAP_CREATED";
    public static final String LOGIN_SUCCEEDED = "ADMIN_LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "ADMIN_LOGIN_FAILED";
    public static final String SYSTEM = "system";

    private final AdminAuditEventRepository events;
    private final Clock clock = Clock.systemUTC();

    /** Written with the caller's transaction (the action and its audit row commit together). */
    @Transactional
    public void success(String action, String actorUserId, String actorLabel, String targetType, String targetId) {
        events.save(new AdminAuditEvent(clock.instant(), actorUserId, actorLabel, action, targetType, targetId, "SUCCESS", null));
    }

    /** Written in its own transaction, so it persists although the request that triggered it fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String action, String actorUserId, String actorLabel, String reason) {
        events.save(new AdminAuditEvent(clock.instant(), actorUserId, actorLabel, action, null, null, "FAILURE", reason));
    }
}
