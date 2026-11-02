package com.agafari.com.service;

import com.agafari.com.dto.response.PublicBusinessMenuResponse;
import com.agafari.com.dto.response.SponsoredAd;
import com.agafari.com.jpa.entity.AgafariAd;
import com.agafari.com.jpa.repository.AgafariAdRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * AGF E4-M7: attaches one Agafari ad to a guest menu. Only with {@code app.monetization.agafari-ads-enabled} on and when the
 * restaurant's existing entitlements say Agafari ads (Model 2; the M3–M6 rules unchanged). The ad is chosen from the ads
 * servable now (ACTIVE, started, not ended) in a stable order, by a daily key of the restaurant and its local date (C3), and
 * counted as a served impression (C5) without ever affecting delivery (C6). Any error means no ad (fail-safe).
 */
@Slf4j
@Service
public class AgafariAdDelivery {

    private final EntitlementService entitlements;
    private final AgafariAdRepository ads;
    private final AgafariAdService adService;
    private final AdImpressionCounter impressions;
    private final MonetizationClock clock;
    private volatile boolean enabled;

    public AgafariAdDelivery(EntitlementService entitlements, AgafariAdRepository ads, AgafariAdService adService, AdImpressionCounter impressions,
                             MonetizationClock clock, @Value("${app.monetization.agafari-ads-enabled:false}") boolean enabled) {
        this.entitlements = entitlements;
        this.ads = ads;
        this.adService = adService;
        this.impressions = impressions;
        this.clock = clock;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Sets {@code sponsored} on the guest menu when eligible and counts the served impression. */
    public void serve(PublicBusinessMenuResponse menu) {
        if (!enabled) return;
        String businessId = menu.getBusiness().getId();
        LocalDate day;
        AgafariAd ad;
        try {
            if (entitlements.forBusiness(businessId).adSource() != Entitlements.AdSource.AGAFARI) return;
            Instant now = clock.now();
            List<AgafariAd> servable = ads.findServable(now);
            if (servable.isEmpty()) return;
            day = LocalDate.ofInstant(now, TimeZoneRules.zoneId(menu.getBusiness().getTimeZone()));
            ad = servable.get(AdRotation.index(businessId, day, servable.size())); // C3, shared with E4-M8
        } catch (RuntimeException e) {
            log.warn("event=ad.not_served businessId={} errorClass={}", businessId, e.getClass().getSimpleName());
            return;
        }
        menu.setSponsored(new SponsoredAd(ad.getId(), ad.getTitle(), ad.getBody(), ad.getTitleAm(), ad.getBodyAm(), ad.getLinkUrl(),
                adService.publicImageUrl(ad.getImageKey()), ad.getImageAlt()));
        impressions.record(ad.getId(), businessId, day); // never throws
    }
}
