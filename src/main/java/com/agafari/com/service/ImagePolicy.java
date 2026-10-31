package com.agafari.com.service;

import com.agafari.com.exception.BadRequestException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side image upload rules (AGF-S1-17, PO-5): JPEG, PNG or WebP only, at most 8 MB.
 * The object key's extension always comes from the content type, never from a client file name.
 */
public final class ImagePolicy {

    public static final long MAX_BYTES = 8L * 1024 * 1024;

    static final String UNSUPPORTED = "Unsupported image type. Upload a JPEG, PNG or WebP image.";
    static final String HEIC = "HEIC photos aren't supported yet. Please upload a JPEG, PNG or WebP image.";
    static final String TOO_LARGE = "Image is too large. The maximum size is 8 MB.";
    static final String NOT_AN_IMAGE = "The uploaded file is not a supported image (JPEG, PNG or WebP).";

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private ImagePolicy() {
    }

    /** Lower-cased media type without parameters, e.g. {@code "image/PNG; q=1"} → {@code "image/png"}. */
    public static String normalize(String contentType) {
        if (contentType == null) return null;
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    /** File extension for an allowed content type; 400 for anything else. */
    public static String extensionFor(String contentType) {
        String type = normalize(contentType);
        if (type != null && (type.equals("image/heic") || type.equals("image/heif"))) {
            throw new BadRequestException(HEIC);
        }
        String ext = type == null ? null : EXTENSIONS.get(type);
        if (ext == null) throw new BadRequestException(UNSUPPORTED);
        return ext;
    }

    /** {@code <prefix>/main.<ext>} for an allowed content type. */
    public static String objectKey(String prefix, String contentType) {
        return prefix + "/main." + extensionFor(contentType);
    }

    /** The extension if {@code key} is exactly {@code <prefix>/main.<jpg|png|webp>}, otherwise {@code null}. */
    public static String extensionOfKey(String key, String prefix) {
        if (key == null) return null;
        Matcher m = Pattern.compile(Pattern.quote(prefix) + "/main\\.(jpg|png|webp)").matcher(key);
        return m.matches() ? m.group(1) : null;
    }

    /** Problem with the stored object (size, type, or type not matching the key), or {@code null} if it is valid. */
    public static String problemWith(HeadObjectResponse head, String expectedExt) {
        Long size = head.contentLength();
        if (size == null || size > MAX_BYTES) return TOO_LARGE;
        String ext = EXTENSIONS.get(normalize(head.contentType()));
        if (ext == null || !ext.equals(expectedExt)) return NOT_AN_IMAGE;
        return null;
    }
}
