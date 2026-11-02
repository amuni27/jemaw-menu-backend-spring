package com.agafari.com.service;

import com.agafari.com.dto.response.PublicBusinessMenuResponse;
import com.agafari.com.dto.response.SponsoredAd;
import com.agafari.com.jpa.entity.RestaurantAd;
import com.agafari.com.jpa.repository.RestaurantAdRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * AGF E4-M8: attaches one of the restaurant's own ads to its guest menu. Only with
 * {@code app.monetization.restaurant-ads-enabled} on and when the restaurant's existing entitlements say restaurant ads
 * (current Model 3; the M3–M6 rules unchanged). Chosen only from this restaurant's ads servable now (ACTIVE, started, not
 * ended) in a stable order, by the shared daily rotation (Q-M8-6). Never when the slot is already filled (Model 2's Agafari
 * ad), so never two ads. Sets the Model 3 privacy notice flag whenever restaurant ads apply, also without a servable ad
 * (Q-M8-12). Nothing is counted (Q-M8-8). Any error means no ad (fail closed).
 */
@Slf4j
@Service
public class RestaurantAdDelivery {

    private final EntitlementService entitlements;
    private final RestaurantAdRepository ads;
    private final RestaurantAdService restaurantAds;
    private final MonetizationClock clock;

    public RestaurantAdDelivery(EntitlementService entitlements, RestaurantAdRepository ads, RestaurantAdService restaurantAds, MonetizationClock clock) {
        this.entitlements = entitlements;
        this.ads = ads;
        this.restaurantAds = restaurantAds;
        this.clock = clock;
    }

    public void serve(PublicBusinessMenuResponse menu) {
        if (!restaurantAds.isEnabled() || menu.getSponsored() != null) return;
        String businessId = menu.getBusiness().getId();
        RestaurantAd ad;
        try {
            if (entitlements.forBusiness(businessId).adSource() != Entitlements.AdSource.RESTAURANT) return;
            menu.setRestaurantAdsNotice(Boolean.TRUE);
            Instant now = clock.now();
            List<RestaurantAd> servable = ads.findServable(businessId, now); // this restaurant's ads only
            if (servable.isEmpty()) return;
            LocalDate day = LocalDate.ofInstant(now, TimeZoneRules.zoneId(menu.getBusiness().getTimeZone()));
            ad = servable.get(AdRotation.index(businessId, day, servable.size()));
        } catch (RuntimeException e) {
            log.warn("event=restaurant_ad.not_served businessId={} errorClass={}", businessId, e.getClass().getSimpleName());
            return;
        }
        menu.setSponsored(new SponsoredAd(ad.getId(), ad.getTitle(), ad.getBody(), ad.getTitleAm(), ad.getBodyAm(), ad.getLinkUrl(),
                restaurantAds.publicImageUrl(ad.getImageKey()), ad.getImageAlt()));
    }
}
