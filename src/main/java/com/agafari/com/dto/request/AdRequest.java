package com.agafari.com.dto.request;

/**
 * AGF E4-M7: an Agafari ad's content and schedule. The schedule is a local date and time in {@code timeZone} (D-M7-1);
 * {@code startOffset}/{@code endOffset} pick the offset of a local time that occurs twice. {@code rowVersion} is required
 * for updates.
 */
public record AdRequest(String title, String body, String titleAm, String bodyAm, String linkUrl, String startLocal, String endLocal,
                        String timeZone, String startOffset, String endOffset, Integer rowVersion) {
}
