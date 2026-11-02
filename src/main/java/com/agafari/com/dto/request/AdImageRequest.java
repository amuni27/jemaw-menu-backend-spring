package com.agafari.com.dto.request;

/** AGF E4-M7: presign ({@code contentType}) or confirm ({@code objectKey}, {@code imageAlt}, {@code rowVersion}) an ad image. */
public record AdImageRequest(String contentType, String objectKey, String imageAlt, Integer rowVersion) {
}
