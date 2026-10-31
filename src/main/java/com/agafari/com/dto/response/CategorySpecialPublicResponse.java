package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * AGF-S2-36 (checkpoint B): a category's active special on guest responses, for the heading badge and the Specials
 * section line. Sent only while it discounts at least one of the category's guest-visible dishes.
 *
 * @param until  today's local end time ("HH:mm") when it has a daily window, else null
 * @param endsAt when the current period ends (instant), for live expiry; null = not today
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // like the item special fields: absent rather than null
public record CategorySpecialPublicResponse(int percent, String note, String noteAm, String until, Instant endsAt) {
}
