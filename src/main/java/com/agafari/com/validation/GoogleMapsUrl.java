package com.agafari.com.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** AGF-S2-12: null or blank (no link), or an accepted Google Maps share link (see {@code GoogleMapsUrls}). */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = GoogleMapsUrlValidator.class)
public @interface GoogleMapsUrl {
    String message() default "Use a Google Maps share link (https://maps.app.goo.gl/… or https://www.google.com/maps/…).";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
