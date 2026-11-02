package com.agafari.com.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AGF E4-M5 (D-M5-4, D-M5-11): the application's only scheduled job: expires due trials, then retries pending trial
 * intents (recovery correction), then (AGF E4-M6) ends grandfathered subscriptions whose approved end has passed. Daily (02:00 UTC by default,
 * {@code app.monetization.trial-expiry-cron}; "-" disables it). Does nothing while trials are disabled; one instance at a
 * time; idempotent, so a missed run is simply caught up by the next one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrialExpiryJob {

    private final TrialService trials;
    private final GrandfatheringService grandfathering;

    @Scheduled(cron = "${app.monetization.trial-expiry-cron:0 0 2 * * *}", zone = "UTC")
    public void run() {
        int expired = trials.runExpiry();
        int recovered = trials.runRecovery();
        int grandfatheringEnded = grandfathering.runEnd(); // E4-M6: its own step and flag (transitions-enabled), independent of trials
        if (expired != 0 || recovered != 0 || grandfatheringEnded != 0) {
            log.info("event=trial.expiry_job expired={} recovered={} grandfatheringEnded={}", expired, recovered, grandfatheringEnded);
        }
    }
}
