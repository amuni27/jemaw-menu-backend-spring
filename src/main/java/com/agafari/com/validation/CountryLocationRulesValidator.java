package com.agafari.com.validation;

import com.agafari.com.dto.request.AuthRegisterRequest;
import com.agafari.com.service.CountryRules;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;

/** AGF-S2-12 CR1: one S1-10 field error per broken rule (country, businessPhone, address fields or locationUrl). */
public class CountryLocationRulesValidator implements ConstraintValidator<CountryLocationRules, AuthRegisterRequest> {

    @Override
    public boolean isValid(AuthRegisterRequest req, ConstraintValidatorContext context) {
        if (req == null) return true;
        Map<String, String> errors = CountryRules.registrationErrors(req.getCountry(), req.getBusinessPhone(),
                req.getStreetAddress(), req.getCity(), req.getState(), req.getZipcode(), req.getLocationUrl());
        if (errors.isEmpty()) return true;
        context.disableDefaultConstraintViolation();
        errors.forEach((field, message) ->
                context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation());
        return false;
    }
}
