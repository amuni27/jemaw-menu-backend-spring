package com.agafari.com.validation;

import com.agafari.com.service.GoogleMapsUrls;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** AGF-S2-12: parses only; never fetches or follows the link. */
public class GoogleMapsUrlValidator implements ConstraintValidator<GoogleMapsUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return GoogleMapsUrls.isBlank(value) || GoogleMapsUrls.normalize(value).isPresent();
    }
}
