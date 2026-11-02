package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AGF E4-M7: the one Agafari ad in a Model 2 restaurant's guest menu. The link is the advertiser's https URL as entered
 * (no redirect through Agafari); Amharic text is optional and the guest app falls back to English.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SponsoredAd(String adId, String title, String body, String titleAm, String bodyAm, String linkUrl, String imageUrl, String imageAlt) {
}
