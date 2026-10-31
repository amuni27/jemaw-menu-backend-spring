package com.agafari.com.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** AGF-S2-12 CR1: registration location fields follow the country (see {@code CountryRules}); errors name the field. */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CountryLocationRulesValidator.class)
public @interface CountryLocationRules {
    String message() default "Check the location fields for the selected country.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
